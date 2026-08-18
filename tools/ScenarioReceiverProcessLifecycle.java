import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/** Bridges systemd SIGTERM to the service-owned coordinator without doing JMS work on a hook. */
final class ScenarioReceiverProcessLifecycle implements AutoCloseable {
    private final ScenarioReceiverService service;
    private final Duration shutdownDeadline;
    private final Duration hookWaitDeadline;
    private final CountDownLatch coordinatorComplete = new CountDownLatch(1);
    private final Signal termSignal = new Signal("TERM");
    private final Thread shutdownHook;
    private SignalHandler previousTermHandler;
    private boolean installed;

    ScenarioReceiverProcessLifecycle(ScenarioReceiverService service,
            Duration shutdownDeadline, Duration hookWaitDeadline) {
        this.service = Objects.requireNonNull(service, "service");
        this.shutdownDeadline = positive(shutdownDeadline, "shutdownDeadline");
        this.hookWaitDeadline = positive(hookWaitDeadline, "hookWaitDeadline");
        if (hookWaitDeadline.compareTo(shutdownDeadline) <= 0) {
            throw new IllegalArgumentException("hook wait deadline must exceed shutdown deadline");
        }
        shutdownHook = new Thread(this::requestAndAwaitCoordinator,
            "scenario-receiver-shutdown-request");
    }

    ScenarioReceiverService.LifecycleState run(Runnable afterStart) throws Exception {
        Objects.requireNonNull(afterStart, "afterStart");
        install();
        try {
            service.start();
            afterStart.run();
            service.awaitShutdownRequest();
            return service.stop(shutdownDeadline);
        } finally {
            coordinatorComplete.countDown();
            close();
        }
    }

    private void install() {
        previousTermHandler = Signal.handle(termSignal, ignored -> service.requestShutdown());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        installed = true;
    }

    void requestAndAwaitCoordinator() {
        service.requestShutdown();
        try {
            coordinatorComplete.await(hookWaitDeadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        if (!installed) return;
        installed = false;
        Signal.handle(termSignal, previousTermHandler);
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // A non-TERM JVM shutdown is already in progress; its hook remains bounded.
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
