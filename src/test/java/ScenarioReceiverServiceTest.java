import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.Topic;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

final class ScenarioReceiverServiceTest {
    private static final String ACCOUNT = "QuakeLogic-SA1";
    private static final String TOPIC = "eew.test_QuakeLogic-SA1.dm.data";

    @Test void normalStartupStateOrder() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});

        service.start();
        fixture.listener.get().onMessage(fixture.message);

        assertEquals(List.of(
            ScenarioReceiverService.LifecycleState.STOPPED,
            ScenarioReceiverService.LifecycleState.STARTING,
            ScenarioReceiverService.LifecycleState.CONNECTING,
            ScenarioReceiverService.LifecycleState.AUTHENTICATING,
            ScenarioReceiverService.LifecycleState.SUBSCRIBED,
            ScenarioReceiverService.LifecycleState.RUNNING), service.stateHistory());
        ScenarioReceiverService.HealthSnapshot health = service.snapshot();
        assertTrue(health.connected());
        assertTrue(health.authenticated());
        assertTrue(health.subscribed());
        assertTrue(health.connectionStarted());
        assertEquals(ACCOUNT, health.accountId());
        assertEquals("scenario-openwire", health.endpointName());
        assertEquals(TOPIC, health.exactDestination());
        assertNotNull(health.processStartedUtc());
        assertNotNull(health.stateEnteredUtc());
        assertEquals(1, health.messagesReceived());
        assertEquals(1, health.capturesCommitted());
        assertEquals(0, health.captureFailures());
        assertFalse(health.shutdownRequested());
    }

    @Test void authenticationFailureTransitionsToFailedAndCleansOwnedResources() {
        Fixture fixture = new Fixture();
        fixture.sessionFailure = new JMSException("offline authentication failure");
        ScenarioReceiverService service = fixture.service(message -> {});

        assertThrows(JMSException.class, service::start);

        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, service.state());
        assertEquals("authentication", service.snapshot().lastErrorCategory());
        assertEquals(List.of("connection"), fixture.closes);
        assertTrue(fixture.instanceLock.released());
    }

    @Test void consumerCreationFailureTransitionsToFailedAndClosesSessionThenConnection() {
        Fixture fixture = new Fixture();
        fixture.consumerFailure = new JMSException("offline consumer failure");
        ScenarioReceiverService service = fixture.service(message -> {});

        assertThrows(JMSException.class, service::start);

        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, service.state());
        assertEquals(List.of("session", "connection"), fixture.closes);
        assertTrue(fixture.instanceLock.released());
    }

    @Test void connectionStartFailureClosesConsumerSessionConnection() {
        Fixture fixture = new Fixture();
        fixture.startFailure = new JMSException("offline start failure");
        ScenarioReceiverService service = fixture.service(message -> {});

        assertThrows(JMSException.class, service::start);

        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, service.state());
        assertEquals("connection_start", service.snapshot().lastErrorCategory());
        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
    }

    @Test void normalShutdownMeetsEveryStoppedCondition() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});
        service.start();

        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED,
            service.stop(Duration.ofSeconds(1)));

        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
        assertTrue(fixture.instanceLock.released());
        ScenarioReceiverService.HealthSnapshot health = service.snapshot();
        assertFalse(health.connected());
        assertFalse(health.authenticated());
        assertFalse(health.subscribed());
        assertFalse(health.connectionStarted());
        assertEquals(0, health.callbacksInProgress());
    }

    @Test void sigtermStyleRequestDuringIdleDoesNotPerformJmsWork() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});
        service.start();

        service.requestShutdown();

        assertTrue(service.snapshot().shutdownRequested());
        assertTrue(fixture.closes.isEmpty(), "request thread must not close JMS resources");
        assertEquals(ScenarioReceiverService.LifecycleState.RUNNING, service.state());
        service.stop(Duration.ofSeconds(1));
        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED, service.state());
    }

    @Test void sigtermWhileCaptureRunsClosesAdmissionThenWaitsForCapture() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScenarioReceiverService service = fixture.service(message -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
        });
        service.start();
        Thread callback = new Thread(() -> fixture.listener.get().onMessage(fixture.message));
        callback.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        service.requestShutdown();
        AtomicReference<ScenarioReceiverService.LifecycleState> result = new AtomicReference<>();
        Thread coordinator = new Thread(() ->
            result.set(service.stop(Duration.ofSeconds(2))));
        coordinator.start();
        awaitClose(fixture.closes, "consumer");
        assertEquals(1, service.snapshot().callbacksInProgress());
        assertFalse(fixture.closes.contains("session"));
        release.countDown();
        callback.join(2000);
        coordinator.join(2000);

        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED, result.get());
        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
    }

    @Test void repeatedShutdownRequestsAndStopsAreIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});
        service.start();

        service.requestShutdown();
        service.requestShutdown();
        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED,
            service.stop(Duration.ofSeconds(1)));
        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED,
            service.stop(Duration.ZERO));

        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
        assertEquals(1, fixture.instanceLock.closeCalls);
    }

    @Test void shutdownDeadlineExpirationFailsClosedWithoutClosingSessionUnderCallback() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScenarioReceiverService service = fixture.service(message -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
        });
        service.start();
        Thread callback = new Thread(() -> fixture.listener.get().onMessage(fixture.message));
        callback.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        assertEquals(ScenarioReceiverService.LifecycleState.FAILED,
            service.stop(Duration.ofMillis(5)));
        assertEquals("shutdown_deadline", service.snapshot().lastErrorCategory());
        assertEquals(List.of("consumer"), fixture.closes);
        assertFalse(fixture.instanceLock.released());

        release.countDown();
        callback.join(2000);
        service.stop(Duration.ofSeconds(1));
        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
        assertTrue(fixture.instanceLock.released());
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, service.state());
    }

    @Test void staleCallbackGenerationIsRejected() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> fail("stale callback ran"));
        service.start();

        assertFalse(service.acceptCallback(service.generation() - 1, fixture.message));
        assertEquals(0, service.snapshot().messagesReceived());
        assertEquals(0, service.snapshot().callbacksInProgress());
    }

    @Test void asynchronousJmsFailureLatchesFailedAndRequestsCoordinatorShutdown() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});
        service.start();

        fixture.exceptionListener.get().onException(new JMSException("raw broker text"));

        ScenarioReceiverService.HealthSnapshot health = service.snapshot();
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, health.lifecycleState());
        assertTrue(health.asyncJmsError());
        assertTrue(health.shutdownRequested());
        assertEquals("async_jms", health.lastErrorCategory());
        assertFalse(health.toString().contains("raw broker text"));
        assertTrue(fixture.closes.isEmpty(), "JMS callback must not perform teardown");
        service.stop(Duration.ofSeconds(1));
    }

    @Test void illegalBackwardLifecycleTransitionFailsClosed() {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});

        assertThrows(IllegalStateException.class,
            () -> service.transitionForTest(ScenarioReceiverService.LifecycleState.CONNECTING));
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, service.state());
        assertEquals("illegal_transition", service.snapshot().lastErrorCategory());
    }

    @Test void resourcesAlwaysCloseConsumerSessionConnectionOrder() throws Exception {
        Fixture fixture = new Fixture();
        ScenarioReceiverService service = fixture.service(message -> {});
        service.start();
        service.requestShutdown();

        service.stop(Duration.ofSeconds(1));

        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
    }

    @Test void failedServiceDoesNotAutomaticallyReconnect() {
        Fixture fixture = new Fixture();
        fixture.sessionFailure = new JMSException("offline authentication failure");
        ScenarioReceiverService service = fixture.service(message -> {});
        assertThrows(JMSException.class, service::start);
        assertEquals(1, fixture.factory.createCalls);

        assertThrows(IllegalStateException.class, service::start);
        assertEquals(1, fixture.factory.createCalls);
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, service.state());
    }

    @Test void captureAndAcknowledgementFailuresFailClosed() throws Exception {
        Fixture captureFailure = new Fixture();
        AtomicInteger captures = new AtomicInteger();
        ScenarioReceiverService captureService = captureFailure.service(message -> {
            captures.incrementAndGet();
            throw new java.io.IOException("private capture detail");
        });
        captureService.start();
        captureFailure.listener.get().onMessage(captureFailure.message);
        captureFailure.listener.get().onMessage(captureFailure.message);
        assertEquals(1, captures.get());
        assertEquals(0, captureFailure.acknowledgementCalls.get());
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, captureService.state());
        assertEquals("capture", captureService.snapshot().lastErrorCategory());
        assertEquals(1, captureService.snapshot().captureFailures());
        assertEquals(1, captureFailure.factory.createCalls);
        captureService.stop(Duration.ofSeconds(1));

        Fixture ackFailure = new Fixture();
        ackFailure.acknowledgementFailure = new JMSException("private acknowledgement detail");
        ScenarioReceiverService ackService = ackFailure.service(message -> {});
        ackService.start();
        ackFailure.listener.get().onMessage(ackFailure.message);
        assertEquals(1, ackService.snapshot().capturesCommitted());
        assertEquals(1, ackFailure.acknowledgementCalls.get());
        assertEquals(0, ackService.snapshot().messagesAcknowledged());
        assertEquals(1, ackService.snapshot().acknowledgementFailures());
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, ackService.state());
        assertTrue(ackFailure.events.contains("ACKNOWLEDGEMENT_FAILED"));
        assertFalse(ackFailure.events.contains("ACKNOWLEDGED"));
        ackFailure.listener.get().onMessage(ackFailure.message);
        assertEquals(1, ackFailure.acknowledgementCalls.get());
        assertEquals(1, ackFailure.factory.createCalls);
        ackService.stop(Duration.ofSeconds(1));
    }

    @Test void acknowledgeWaitsForCaptureAndEveryDeliveryGetsOneAttempt() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        ScenarioReceiverService service = fixture.service(message -> {
            captureEntered.countDown();
            assertTrue(releaseCapture.await(2, TimeUnit.SECONDS));
        });
        service.start();
        Thread callback = new Thread(() -> fixture.listener.get().onMessage(fixture.message));
        callback.start();
        assertTrue(captureEntered.await(1, TimeUnit.SECONDS));
        assertEquals(0, fixture.acknowledgementCalls.get());
        releaseCapture.countDown();
        callback.join(2000);
        fixture.listener.get().onMessage(fixture.message);
        assertEquals(2, service.snapshot().capturesCommitted());
        assertEquals(2, fixture.acknowledgementCalls.get());
        assertEquals(2, service.snapshot().messagesAcknowledged());
        service.stop(Duration.ofSeconds(1));
    }

    @Test void shutdownDuringAckWaitsAndDeadlineFailureNeverClaimsStopped() throws Exception {
        Fixture healthy = new Fixture();
        healthy.acknowledgementEntered = new CountDownLatch(1);
        healthy.acknowledgementRelease = new CountDownLatch(1);
        ScenarioReceiverService service = healthy.service(message -> {});
        service.start();
        Thread callback = new Thread(() -> healthy.listener.get().onMessage(healthy.message));
        callback.start();
        assertTrue(healthy.acknowledgementEntered.await(1, TimeUnit.SECONDS));
        service.requestShutdown();
        AtomicReference<ScenarioReceiverService.LifecycleState> result = new AtomicReference<>();
        Thread coordinator = new Thread(() -> result.set(service.stop(Duration.ofSeconds(2))));
        coordinator.start();
        awaitClose(healthy.closes, "consumer");
        assertFalse(healthy.closes.contains("session"));
        healthy.acknowledgementRelease.countDown();
        callback.join(2000); coordinator.join(2000);
        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED, result.get());

        Fixture expired = new Fixture();
        expired.acknowledgementEntered = new CountDownLatch(1);
        expired.acknowledgementRelease = new CountDownLatch(1);
        ScenarioReceiverService expiredService = expired.service(message -> {});
        expiredService.start();
        Thread expiredCallback = new Thread(() -> expired.listener.get().onMessage(expired.message));
        expiredCallback.start();
        assertTrue(expired.acknowledgementEntered.await(1, TimeUnit.SECONDS));
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, expiredService.stop(Duration.ofMillis(5)));
        assertEquals(List.of("consumer"), expired.closes);
        assertFalse(expired.events.contains("STOPPED"));
        expired.acknowledgementRelease.countDown();
        expiredCallback.join(2000);
        expiredService.stop(Duration.ofSeconds(1));
        assertEquals(ScenarioReceiverService.LifecycleState.FAILED, expiredService.state());
        assertFalse(expired.events.contains("STOPPED"));
    }

    @Test void expectedAndUnexpectedParserOutcomesOccurAfterAcknowledgement() throws Exception {
        for (boolean unexpected : List.of(false, true)) {
            Fixture fixture = new Fixture();
            ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(envelope -> {
                if (unexpected) throw new IllegalStateException("private parser detail");
                throw new ShakeAlertEventParser.ExpectedFailure(
                    ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
            });
            AtomicReference<ShakeAlertEventProcessor.Outcome> outcome = new AtomicReference<>();
            ScenarioReceiverService service = new ScenarioReceiverService(
                fixture.factory, ACCOUNT, "scenario-openwire", TOPIC,
                (message, generation) -> new NativeCaptureCommit(new byte[]{1},
                    java.time.Instant.EPOCH, "capture", "capture.json", null, null, false),
                (commit, generation) -> {
                    assertTrue(fixture.events.contains("ACKNOWLEDGED"));
                    MessageEnvelope envelope = new MessageEnvelope(commit.payload(),
                        commit.receivedAtUtc(), commit.captureId(), commit.captureReference(),
                        "scenario", "scenario.eew.shakealert.org:61612", TOPIC, ACCOUNT,
                        commit.jmsMessageId(), commit.brokerTimestamp(), commit.redelivered(),
                        java.util.Map.of(), generation);
                    outcome.set(processor.process(envelope));
                }, snapshot -> {}, fixture.instanceLock, fixture.events::add);
            service.start();
            fixture.listener.get().onMessage(fixture.message);
            assertEquals(1, fixture.acknowledgementCalls.get());
            assertEquals(unexpected ? ShakeAlertEventParser.FailureCategory.PARSER_FAILURE
                    : ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA,
                outcome.get().rejection());
            assertEquals(unexpected ? ShakeAlertEventProcessor.State.FAILED
                    : ShakeAlertEventProcessor.State.RUNNING, processor.state());
            service.stop(Duration.ofSeconds(1));
        }
    }

    @Test void nativeCaptureCommitDefensivelyOwnsPayload() {
        byte[] input = new byte[]{1, 2};
        NativeCaptureCommit commit = new NativeCaptureCommit(input, java.time.Instant.EPOCH,
            "capture", "capture.json", null, null, true);
        input[0] = 9;
        byte[] returned = commit.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, commit.payload());
        assertTrue(commit.redelivered());
    }

    @Test void callbackCommitsCaptureBeforeEnvelopeProcessingAndLeaksNoJmsObject() throws Exception {
        Fixture fixture = new Fixture();
        List<String> order = new ArrayList<>();
        ScenarioReceiverService service = new ScenarioReceiverService(
            fixture.factory, ACCOUNT, "scenario-openwire", TOPIC,
            (message, generation) -> {
                order.add("MESSAGE_CALLBACK");
                return new NativeCaptureCommit(new byte[]{1}, java.time.Instant.EPOCH,
                    "capture", "capture.json", null, null, false);
            },
            (committed, generation) -> {
                order.add("ENVELOPE_CREATED");
                assertFalse(committed.getClass().getName().startsWith("javax.jms"));
                order.add("PARSING_BEGINS");
            }, snapshot -> {}, fixture.instanceLock, order::add);
        service.start();

        fixture.listener.get().onMessage(fixture.message);

        assertEquals(List.of("MESSAGE_CALLBACK", "CAPTURE_COMMITTED",
            "ACKNOWLEDGEMENT_STARTED", "ACKNOWLEDGED",
            "ENVELOPE_CREATED", "PARSING_BEGINS"), order);
        assertEquals(1, fixture.acknowledgementCalls.get());
        service.stop(Duration.ofSeconds(1));
    }

    private static void awaitClose(List<String> closes, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!closes.contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(closes.contains(expected));
    }

    private static final class Fixture {
        final List<String> closes = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<MessageListener> listener = new AtomicReference<>();
        final AtomicReference<ExceptionListener> exceptionListener = new AtomicReference<>();
        final FakeInstanceLock instanceLock = new FakeInstanceLock();
        final AtomicInteger acknowledgementCalls = new AtomicInteger();
        volatile JMSException acknowledgementFailure;
        volatile CountDownLatch acknowledgementEntered;
        volatile CountDownLatch acknowledgementRelease;
        final Message message = proxy(Message.class, (method, args) -> {
            if (method.getName().equals("acknowledge")) {
                acknowledgementCalls.incrementAndGet();
                events.add("ACK_CALL");
                if (acknowledgementEntered != null) acknowledgementEntered.countDown();
                if (acknowledgementRelease != null) acknowledgementRelease.await(2, TimeUnit.SECONDS);
                if (acknowledgementFailure != null) throw acknowledgementFailure;
                return null;
            }
            return defaultValue(method.getReturnType());
        });
        JMSException sessionFailure;
        JMSException consumerFailure;
        JMSException startFailure;
        final FakeFactory factory;

        Fixture() {
            Topic topic = proxy(Topic.class, (method, args) ->
                method.getName().equals("getTopicName") ? TOPIC : defaultValue(method.getReturnType()));
            MessageConsumer consumer = proxy(MessageConsumer.class, (method, args) -> {
                if (method.getName().equals("setMessageListener")) {
                    listener.set((MessageListener) args[0]); return null;
                }
                if (method.getName().equals("close")) { closes.add("consumer"); return null; }
                return defaultValue(method.getReturnType());
            });
            Session session = proxy(Session.class, (method, args) -> {
                if (method.getName().equals("createTopic")) {
                    assertEquals(TOPIC, args[0]); return topic;
                }
                if (method.getName().equals("createConsumer")) {
                    assertSame(topic, args[0]); assertNull(args[1]); assertEquals(false, args[2]);
                    if (consumerFailure != null) throw consumerFailure;
                    return consumer;
                }
                if (method.getName().equals("close")) { closes.add("session"); return null; }
                return defaultValue(method.getReturnType());
            });
            Connection connection = proxy(Connection.class, (method, args) -> {
                if (method.getName().equals("setExceptionListener")) {
                    exceptionListener.set((ExceptionListener) args[0]); return null;
                }
                if (method.getName().equals("createSession")) {
                    assertEquals(false, args[0]);
                    assertEquals(Session.CLIENT_ACKNOWLEDGE, args[1]);
                    if (sessionFailure != null) throw sessionFailure;
                    return session;
                }
                if (method.getName().equals("start")) {
                    if (startFailure != null) throw startFailure;
                    return null;
                }
                if (method.getName().equals("close")) { closes.add("connection"); return null; }
                return defaultValue(method.getReturnType());
            });
            factory = new FakeFactory(connection);
        }

        ScenarioReceiverService service(TestCapture capture) {
            return new ScenarioReceiverService(
                factory, ACCOUNT, "scenario-openwire", TOPIC,
                (message, generation) -> {
                    capture.capture(message);
                    return new NativeCaptureCommit(new byte[]{1}, java.time.Instant.EPOCH,
                    "capture", "capture.json", null, null, false);
                },
                (committed, generation) -> {}, snapshot -> {}, instanceLock, events::add);
        }

        interface TestCapture { void capture(Message message) throws Exception; }
    }

    private static final class FakeFactory extends ActiveMQConnectionFactory {
        private final Connection connection;
        int createCalls;
        FakeFactory(Connection connection) { this.connection = connection; }
        @Override public Connection createConnection() {
            createCalls++;
            return connection;
        }
    }

    private static final class FakeInstanceLock implements ScenarioReceiverService.InstanceLock {
        boolean released;
        int closeCalls;
        @Override public boolean released() { return released; }
        @Override public void close() { closeCalls++; released = true; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type},
            (instance, method, args) -> invocation.call(method, args));
    }

    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
