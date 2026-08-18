import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.Topic;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

final class ScenarioReceiverProcessLifecycleTest {
    @Test void hookStyleRequestWakesCoordinatorAndReturnsOnlyAfterStopped() throws Exception {
        OfflineFixture fixture = new OfflineFixture(false, Duration.ofSeconds(1));
        AtomicReference<ScenarioReceiverService.LifecycleState> finalState = new AtomicReference<>();
        ScenarioReceiverProcessLifecycle lifecycle = new ScenarioReceiverProcessLifecycle(
            fixture.service, Duration.ofSeconds(1), Duration.ofSeconds(2));
        Thread coordinator = new Thread(() -> {
            try {
                finalState.set(lifecycle.run(fixture.running::countDown));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }, "test-service-coordinator");
        coordinator.start();
        assertTrue(fixture.running.await(2, TimeUnit.SECONDS));

        Thread hook = new Thread(lifecycle::requestAndAwaitCoordinator, "test-shutdown-hook");
        hook.start();
        coordinator.join(2000);
        hook.join(2000);

        assertFalse(coordinator.isAlive());
        assertFalse(hook.isAlive());
        assertEquals(ScenarioReceiverService.LifecycleState.STOPPED, finalState.get());
        assertEquals(List.of("consumer", "session", "connection"), fixture.closes);
        assertEventOrder(fixture.events);
    }

    @Test void actualSigtermStopsOfflineFakeTransportAndExitsZero() throws Exception {
        SubprocessResult result = runFixture("success");
        assertEquals(0, result.exitCode, result.output.toString());
        assertTrue(result.output.contains("FINAL_STATE=STOPPED"));
        assertTrue(result.output.contains("CLOSE_ORDER=consumer,session,connection"));
        assertOutputOrder(result.output);
    }

    @Test void actualSigtermDeadlineFailureExitsNonzeroWithoutStopped() throws Exception {
        SubprocessResult result = runFixture("deadline");
        assertTrue(result.exitCode != 0, result.output.toString());
        assertTrue(result.output.contains("FINAL_STATE=FAILED"));
        assertTrue(result.output.contains("EVENT=STOPPING"));
        assertFalse(result.output.contains("EVENT=STOPPED"));
    }

    private static SubprocessResult runFixture(String mode) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path outputFile = Files.createTempFile("scenario-sigterm-fixture-", ".log");
        try {
            Process process = new ProcessBuilder(javaExecutable, "-cp",
                System.getProperty("java.class.path"), FixtureMain.class.getName(), mode)
                .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<String> output = List.of();
            while (System.nanoTime() < deadline) {
                output = Files.readAllLines(outputFile);
                if (output.contains("READY")) break;
                Thread.sleep(10);
            }
            assertTrue(output.contains("READY"), output.toString());
            process.destroy(); // POSIX SIGTERM on this Linux test host.
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), output.toString());
            output = Files.readAllLines(outputFile);
            return new SubprocessResult(process.exitValue(), output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static void assertOutputOrder(List<String> output) {
        assertEventOrder(output.stream().map(value -> value.startsWith("EVENT=")
            ? value.substring("EVENT=".length()) : value).toList());
    }

    private static void assertEventOrder(java.util.Collection<String> output) {
        List<String> expected = List.of("SHUTDOWN_REQUESTED", "STOPPING",
            "CALLBACK_ADMISSION_CLOSED", "CONSUMER_CLOSED", "CALLBACK_DRAIN_COMPLETE",
            "SESSION_CLOSED", "CONNECTION_CLOSED", "INSTANCE_LOCK_RELEASED", "STOPPED");
        int position = -1;
        List<String> values = new ArrayList<>(output);
        for (String event : expected) {
            int found = values.subList(position + 1, values.size()).indexOf(event);
            assertTrue(found >= 0, "missing/out-of-order " + event + " in " + values);
            position += found + 1;
        }
    }

    private record SubprocessResult(int exitCode, List<String> output) {}

    public static final class FixtureMain {
        public static void main(String[] args) throws Exception {
            boolean deadline = args.length == 1 && args[0].equals("deadline");
            OfflineFixture fixture = new OfflineFixture(deadline,
                deadline ? Duration.ofMillis(25) : Duration.ofSeconds(2));
            ScenarioReceiverProcessLifecycle lifecycle = new ScenarioReceiverProcessLifecycle(
                fixture.service, fixture.shutdownDeadline, fixture.shutdownDeadline.plusSeconds(1));
            ScenarioReceiverService.LifecycleState state = lifecycle.run(() -> {
                if (deadline) fixture.startBlockedCallback();
                System.out.println("READY");
                System.out.flush();
            });
            System.out.println("FINAL_STATE=" + state);
            System.out.println("CLOSE_ORDER=" + String.join(",", fixture.closes));
            System.out.flush();
            if (state != ScenarioReceiverService.LifecycleState.STOPPED) {
                throw new IllegalStateException("sanitized shutdown deadline failure");
            }
        }
    }

    private static final class OfflineFixture {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> closes = java.util.Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch callbackEntered = new CountDownLatch(1);
        final CountDownLatch blockCallback = new CountDownLatch(1);
        final AtomicReference<MessageListener> listener = new AtomicReference<>();
        final Duration shutdownDeadline;
        final ScenarioReceiverService service;

        OfflineFixture(boolean blockCapture, Duration shutdownDeadline) {
            this.shutdownDeadline = shutdownDeadline;
            MessageConsumer consumer = proxy(MessageConsumer.class, (method, arguments) -> {
                if (method.getName().equals("setMessageListener")) {
                    listener.set((MessageListener) arguments[0]);
                } else if (method.getName().equals("close")) {
                    closes.add("consumer");
                }
                return null;
            });
            Topic topic = proxy(Topic.class, (method, arguments) -> null);
            Session session = proxy(Session.class, (method, arguments) -> {
                if (method.getName().equals("createTopic")) return topic;
                if (method.getName().equals("createConsumer")) return consumer;
                if (method.getName().equals("close")) closes.add("session");
                return defaultValue(method.getReturnType());
            });
            Connection connection = proxy(Connection.class, (method, arguments) -> {
                if (method.getName().equals("createSession")) return session;
                if (method.getName().equals("close")) closes.add("connection");
                return defaultValue(method.getReturnType());
            });
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory() {
                @Override public Connection createConnection() { return connection; }
            };
            ScenarioReceiverService.InstanceLock lock = new ScenarioReceiverService.InstanceLock() {
                private boolean released;
                @Override public boolean released() { return released; }
                @Override public void close() { released = true; }
            };
            service = new ScenarioReceiverService(factory, "QuakeLogic-SA1", "offline-fake",
                "eew.test_QuakeLogic-SA1.dm.data", (message, generation) -> {
                    callbackEntered.countDown();
                    if (blockCapture) blockCallback.await();
                    return new MessageEnvelope(new byte[]{1}, java.time.Instant.EPOCH,
                        "offline-capture", "offline-capture.json", "scenario", "offline-fake",
                        "eew.test_QuakeLogic-SA1.dm.data", "QuakeLogic-SA1", null, null,
                        false, java.util.Map.of(), generation);
                }, envelope -> {}, snapshot -> {}, lock, event -> {
                    events.add(event);
                    System.out.println("EVENT=" + event);
                    System.out.flush();
                });
        }

        void startBlockedCallback() {
            Message message = proxy(Message.class, (method, arguments) ->
                defaultValue(method.getReturnType()));
            Thread callback = new Thread(() -> listener.get().onMessage(message),
                "offline-fake-callback");
            callback.setDaemon(true);
            callback.start();
            try {
                if (!callbackEntered.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("offline callback did not enter");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("offline callback wait interrupted", error);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (instance, method, arguments) -> invocation.call(method, arguments));
    }

    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
