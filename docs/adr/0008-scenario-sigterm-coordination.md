# ADR 0008: Scenario SIGTERM coordination

## Status

Accepted for offline implementation; reinstall and controlled service stop require separate authorization.

## Context

The first managed-service stop released the process and socket but systemd recorded exit status 143 and no shutdown lifecycle evidence. An offline Java subprocess experiment proved that direct JVM SIGTERM retains status 143 even when a shutdown hook requests work, the main coordinator completes it, and the hook returns. The service also had no lifecycle event sink for shutdown resource closure, so ordered teardown was unobservable.

## Decision

`ScenarioReceiverProcessLifecycle` is the single process-lifecycle bridge. For systemd SIGTERM it uses the Java 21 `sun.misc.Signal` API, isolated to that class, to convert TERM into `requestShutdown()` without beginning JVM shutdown. The main thread remains the coordinator, wakes from `awaitShutdownRequest()`, and performs the existing bounded `stop()` algorithm. It then returns normally so the JVM exits 0.

The signal callback never performs JMS work. A JVM shutdown hook remains for non-TERM shutdown: it requests shutdown and waits at most 35 seconds for coordinator completion. The coordinator owns the 30-second teardown deadline. No normal path calls `System.exit()` or `Runtime.halt()`.

Use of `sun.misc.Signal` is an explicit, narrow exception to the preference for supported Java APIs. Standard Java exposes shutdown hooks but no supported pre-shutdown POSIX TERM callback, and hooks cannot change the signal-derived exit status. A real Linux subprocess test sends OS SIGTERM to an offline fake-JMS activation and verifies exit 0, lifecycle evidence, close ordering, and quiescence. A failure fixture verifies nonzero exit and absence of `STOPPED`.

## Consequences

Normal systemd TERM is:

```text
SHUTDOWN_REQUESTED -> STOPPING -> CALLBACK_ADMISSION_CLOSED
-> CONSUMER_CLOSED -> CALLBACK_DRAIN_COMPLETE -> SESSION_CLOSED
-> CONNECTION_CLOSED -> INSTANCE_LOCK_RELEASED -> STOPPED -> exit 0
```

Deadline failure latches `FAILED`, never emits `STOPPED`, and exits nonzero. `Type=simple`, `KillSignal=SIGTERM`, `TimeoutStopSec=45s`, `SendSIGKILL=no`, and `Restart=no` remain appropriate. No `ExecStop` workaround is required. Transport, capture, parser, subscription, and broker semantics are unchanged.
