import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.Topic;

import org.apache.activemq.ActiveMQConnectionFactory;

/** Owns one passive Scenario OpenWire activation and its complete lifecycle. */
final class ScenarioReceiverService {
    enum LifecycleState {
        STOPPED, STARTING, CONNECTING, AUTHENTICATING, SUBSCRIBED, RUNNING,
        STOPPING, FAILED
    }

    @FunctionalInterface
    interface CaptureHandler {
        MessageEnvelope capture(Message message, long activationGeneration) throws Exception;
    }


    @FunctionalInterface
    interface EnvelopeHandler {
        void process(MessageEnvelope envelope) throws Exception;
    }

    @FunctionalInterface
    interface HealthSink { void publish(HealthSnapshot snapshot); }

    interface InstanceLock extends AutoCloseable {
        boolean released();
        @Override void close() throws Exception;
    }

    record HealthSnapshot(
        LifecycleState lifecycleState,
        Instant stateEnteredUtc,
        Instant processStartedUtc,
        boolean connected,
        boolean authenticated,
        boolean subscribed,
        boolean connectionStarted,
        String accountId,
        String endpointName,
        String exactDestination,
        long messagesReceived,
        long capturesCommitted,
        long captureFailures,
        int callbacksInProgress,
        boolean asyncJmsError,
        String lastErrorCategory,
        Instant lastErrorUtc,
        boolean shutdownRequested
    ) {}

    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    private final Object monitor = new Object();
    private final ActiveMQConnectionFactory factory;
    private final String accountId;
    private final String endpointName;
    private final String exactDestination;
    private final CaptureHandler captureHandler;
    private final EnvelopeHandler envelopeHandler;
    private final HealthSink healthSink;
    private final InstanceLock instanceLock;
    private final long generation;
    private final Instant processStartedUtc;
    private final List<LifecycleState> stateHistory = new ArrayList<>();

    private LifecycleState state = LifecycleState.STOPPED;
    private Instant stateEnteredUtc;
    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private boolean callbackAdmissionOpen;
    private boolean connected;
    private boolean authenticated;
    private boolean subscribed;
    private boolean connectionStarted;
    private boolean consumerClosed = true;
    private boolean sessionClosed = true;
    private boolean connectionClosed = true;
    private boolean shutdownRequested;
    private int callbacksInProgress;
    private long messagesReceived;
    private long capturesCommitted;
    private long captureFailures;
    private boolean asyncJmsError;
    private String lastErrorCategory;
    private Instant lastErrorUtc;

    ScenarioReceiverService(
        ActiveMQConnectionFactory factory,
        String accountId,
        String endpointName,
        String exactDestination,
        CaptureHandler captureHandler,
        EnvelopeHandler envelopeHandler,
        HealthSink healthSink,
        InstanceLock instanceLock
    ) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.endpointName = Objects.requireNonNull(endpointName, "endpointName");
        this.exactDestination = Objects.requireNonNull(exactDestination, "exactDestination");
        this.captureHandler = Objects.requireNonNull(captureHandler, "captureHandler");
        this.envelopeHandler = Objects.requireNonNull(envelopeHandler, "envelopeHandler");
        this.healthSink = Objects.requireNonNull(healthSink, "healthSink");
        this.instanceLock = Objects.requireNonNull(instanceLock, "instanceLock");
        this.generation = NEXT_GENERATION.incrementAndGet();
        this.processStartedUtc = Instant.now();
        this.stateEnteredUtc = processStartedUtc;
        stateHistory.add(LifecycleState.STOPPED);
    }

    long generation() {
        return generation;
    }

    List<LifecycleState> stateHistory() {
        synchronized (monitor) {
            return List.copyOf(stateHistory);
        }
    }

    LifecycleState state() {
        synchronized (monitor) {
            return state;
        }
    }

    void start() throws Exception {
        synchronized (monitor) {
            transition(LifecycleState.STARTING);
        }
        try {
            synchronized (monitor) {
                transition(LifecycleState.CONNECTING);
            }
            Connection createdConnection = factory.createConnection();
            synchronized (monitor) {
                connection = createdConnection;
                connectionClosed = false;
                connected = true;
                createdConnection.setExceptionListener(this::onAsyncJmsException);
                transition(LifecycleState.AUTHENTICATING);
            }

            Session createdSession = createdConnection.createSession(
                false, Session.CLIENT_ACKNOWLEDGE);
            synchronized (monitor) {
                session = createdSession;
                sessionClosed = false;
                authenticated = true;
            }

            Topic topic = createdSession.createTopic(exactDestination);
            MessageConsumer createdConsumer = createdSession.createConsumer(topic, null, false);
            long activationGeneration = generation;
            createdConsumer.setMessageListener(
                message -> acceptCallback(activationGeneration, message));
            synchronized (monitor) {
                consumer = createdConsumer;
                consumerClosed = false;
                subscribed = true;
                transition(LifecycleState.SUBSCRIBED);
            }

            createdConnection.start();
            synchronized (monitor) {
                connectionStarted = true;
                callbackAdmissionOpen = true;
                transition(LifecycleState.RUNNING);
            }
        } catch (Exception error) {
            fail(categoryForStartupState(), false);
            closeAfterStartupFailure();
            throw error;
        }
    }

    void requestShutdown() {
        synchronized (monitor) {
            shutdownRequested = true;
            publishHealthLocked();
            monitor.notifyAll();
        }
    }

    void awaitShutdownRequest() throws InterruptedException {
        synchronized (monitor) {
            while (!shutdownRequested && state != LifecycleState.FAILED) {
                monitor.wait();
            }
        }
    }

    boolean awaitCoordinatorTeardown(Duration deadline) throws InterruptedException {
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative()) throw new IllegalArgumentException("deadline must not be negative");
        long remainingNanos = deadline.toNanos();
        long started = System.nanoTime();
        synchronized (monitor) {
            while (!stoppedConditions() && remainingNanos > 0) {
                long millis = Math.max(1L, Math.min(
                    Duration.ofNanos(remainingNanos).toMillis(), 100L));
                monitor.wait(millis);
                remainingNanos = deadline.toNanos() - (System.nanoTime() - started);
            }
            return stoppedConditions();
        }
    }

    LifecycleState stop(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative()) throw new IllegalArgumentException("deadline must not be negative");
        long remainingNanos = deadline.toNanos();
        long started = System.nanoTime();

        synchronized (monitor) {
            shutdownRequested = true;
            if (state == LifecycleState.STOPPED) return state;
            if (state != LifecycleState.FAILED && state != LifecycleState.STOPPING) {
                transition(LifecycleState.STOPPING);
            }
            callbackAdmissionOpen = false;
        }

        closeConsumer();

        synchronized (monitor) {
            while (callbacksInProgress != 0 && remainingNanos > 0) {
                long millis = Math.max(1L, Math.min(
                    Duration.ofNanos(remainingNanos).toMillis(), 100L));
                try {
                    monitor.wait(millis);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failLocked("shutdown_interrupted", false);
                    return state;
                }
                remainingNanos = deadline.toNanos() - (System.nanoTime() - started);
            }
            if (callbacksInProgress != 0) {
                failLocked("shutdown_deadline", false);
                return state;
            }
        }

        closeSession();
        closeConnection();
        closeInstanceLock();

        synchronized (monitor) {
            if (stoppedConditions()) {
                if (state != LifecycleState.FAILED) {
                    transition(LifecycleState.STOPPED);
                }
            } else {
                failLocked("shutdown_incomplete", false);
            }
            publishHealthLocked();
            monitor.notifyAll();
            return state;
        }
    }

    boolean acceptCallback(long callbackGeneration, Message message) {
        synchronized (monitor) {
            if (callbackGeneration != generation
                    || !callbackAdmissionOpen
                    || state != LifecycleState.RUNNING) {
                return false;
            }
            callbacksInProgress++;
            messagesReceived++;
            publishHealthLocked();
        }
        try {
            MessageEnvelope envelope;
            try {
                envelope = captureHandler.capture(message, callbackGeneration);
            } catch (Exception error) {
                synchronized (monitor) {
                    captureFailures++;
                    recordErrorLocked("capture");
                }
                return false;
            }
            synchronized (monitor) {
                capturesCommitted++;
                publishHealthLocked();
            }
            try {
                envelopeHandler.process(envelope);
            } catch (Exception error) {
                synchronized (monitor) { recordErrorLocked("post_capture_processing"); }
            }
            return true;
        } finally {
            synchronized (monitor) {
                callbacksInProgress--;
                publishHealthLocked();
                monitor.notifyAll();
            }
        }
    }

    HealthSnapshot snapshot() {
        synchronized (monitor) { return snapshotLocked(); }
    }

    private HealthSnapshot snapshotLocked() {
        return new HealthSnapshot(
            state, stateEnteredUtc, processStartedUtc, connected,
            authenticated, subscribed, connectionStarted, accountId,
            endpointName, exactDestination, messagesReceived,
            capturesCommitted, captureFailures, callbacksInProgress,
            asyncJmsError, lastErrorCategory, lastErrorUtc, shutdownRequested);
    }

    private void publishHealthLocked() {
        try {
            healthSink.publish(snapshotLocked());
        } catch (RuntimeException error) {
            callbackAdmissionOpen = false;
            recordErrorLocked("health_publish");
            if (state != LifecycleState.FAILED) {
                state = LifecycleState.FAILED;
                stateEnteredUtc = Instant.now();
                stateHistory.add(LifecycleState.FAILED);
            }
        }
    }

    void transitionForTest(LifecycleState next) {
        synchronized (monitor) {
            transition(next);
        }
    }

    private void onAsyncJmsException(javax.jms.JMSException ignored) {
        synchronized (monitor) {
            asyncJmsError = true;
            shutdownRequested = true;
            failLocked("async_jms", true);
            monitor.notifyAll();
        }
    }

    private String categoryForStartupState() {
        synchronized (monitor) {
            return switch (state) {
                case AUTHENTICATING -> authenticated ? "subscription" : "authentication";
                case SUBSCRIBED -> "connection_start";
                case CONNECTING -> "connection";
                default -> "startup";
            };
        }
    }

    private void fail(String category, boolean async) {
        synchronized (monitor) {
            failLocked(category, async);
        }
    }

    private void failLocked(String category, boolean async) {
        callbackAdmissionOpen = false;
        if (async) asyncJmsError = true;
        recordErrorLocked(category);
        if (state != LifecycleState.FAILED) {
            state = LifecycleState.FAILED;
            stateEnteredUtc = Instant.now();
            stateHistory.add(LifecycleState.FAILED);
        }
        publishHealthLocked();
    }

    private void recordErrorLocked(String category) {
        lastErrorCategory = category;
        lastErrorUtc = Instant.now();
    }

    private void closeAfterStartupFailure() {
        synchronized (monitor) {
            callbackAdmissionOpen = false;
        }
        closeConsumer();
        closeSession();
        closeConnection();
        closeInstanceLock();
        synchronized (monitor) { publishHealthLocked(); }
    }

    private void closeConsumer() {
        MessageConsumer resource;
        synchronized (monitor) {
            resource = consumer;
            consumer = null;
        }
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception error) {
                fail("consumer_close", false);
                return;
            }
        }
        synchronized (monitor) {
            consumerClosed = true;
            subscribed = false;
        }
    }

    private void closeSession() {
        Session resource;
        synchronized (monitor) {
            resource = session;
            session = null;
        }
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception error) {
                fail("session_close", false);
                return;
            }
        }
        synchronized (monitor) {
            sessionClosed = true;
            authenticated = false;
        }
    }

    private void closeConnection() {
        Connection resource;
        synchronized (monitor) {
            resource = connection;
            connection = null;
        }
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception error) {
                fail("connection_close", false);
                return;
            }
        }
        synchronized (monitor) {
            connectionClosed = true;
            connectionStarted = false;
            connected = false;
        }
    }

    private void closeInstanceLock() {
        try {
            if (!instanceLock.released()) instanceLock.close();
        } catch (Exception error) {
            fail("instance_lock_release", false);
        }
    }

    private boolean stoppedConditions() {
        return !callbackAdmissionOpen
            && callbacksInProgress == 0
            && consumerClosed
            && sessionClosed
            && connectionClosed
            && instanceLock.released();
    }

    private void transition(LifecycleState next) {
        if (!legalTransition(state, next)) {
            failLocked("illegal_transition", false);
            throw new IllegalStateException(
                "illegal lifecycle transition " + state + " -> " + next);
        }
        state = next;
        stateEnteredUtc = Instant.now();
        stateHistory.add(next);
        publishHealthLocked();
    }

    static boolean legalTransition(LifecycleState from, LifecycleState to) {
        if (to == LifecycleState.FAILED) {
            return from != LifecycleState.STOPPED && from != LifecycleState.FAILED;
        }
        return switch (from) {
            case STOPPED -> to == LifecycleState.STARTING;
            case STARTING -> to == LifecycleState.CONNECTING;
            case CONNECTING -> to == LifecycleState.AUTHENTICATING;
            case AUTHENTICATING -> to == LifecycleState.SUBSCRIBED;
            case SUBSCRIBED -> to == LifecycleState.RUNNING;
            case RUNNING -> to == LifecycleState.STOPPING;
            case STOPPING -> to == LifecycleState.STOPPED;
            case FAILED -> false;
        };
    }

    static InstanceLock acquireInstanceLock(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("instance lock has no parent directory");
        Files.createDirectories(parent);
        FileChannel channel = FileChannel.open(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (RuntimeException | IOException error) {
            channel.close();
            throw error;
        }
        if (lock == null) {
            channel.close();
            throw new IOException("Scenario receiver instance lock is already held");
        }
        return new InstanceLock() {
            private boolean released;
            @Override public synchronized boolean released() { return released; }
            @Override public synchronized void close() throws IOException {
                if (released) return;
                try {
                    lock.release();
                } finally {
                    channel.close();
                    released = true;
                }
            }
        };
    }
}
