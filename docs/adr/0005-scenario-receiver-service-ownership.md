# ADR 0005: Scenario Receiver Service Ownership

## Status

Accepted

## Decision

`ScenarioReceiverService` exclusively owns one passive Scenario activation:
the ActiveMQ connection factory, JMS connection, session, exact Topic consumer,
message listener, instance lock, callback admission, health counters, and
shutdown coordination.

The authoritative lifecycle is:

```text
STOPPED -> STARTING -> CONNECTING -> AUTHENTICATING
        -> SUBSCRIBED -> RUNNING -> STOPPING -> STOPPED
```

An appropriate failure from a non-terminal state latches `FAILED`. Illegal
backward transitions fail closed. A failed instance cannot restart or reconnect
itself.

The JVM shutdown hook only requests shutdown and waits for a bounded coordinator
completion condition. The main service coordinator closes callback admission,
closes the consumer, waits for already-admitted capture callbacks up to the
deadline, then closes the session and connection and releases the instance
lock. Resource close order is always consumer, session, connection.

`STOPPED` is valid only after callback admission is closed, no capture callback
is in progress, all three JMS resources are closed, and the instance lock is
released. Deadline expiration latches `FAILED` without closing the session or
connection underneath an active capture callback; cleanup may be requested
again after that callback exits, but the state remains `FAILED`.

Every service instance receives a monotonically increasing generation. The
installed listener captures that generation, and callbacks with any other
generation are rejected before counters or capture work.

## Safety boundary

This ownership refactor does not change the verified broker URL, TLS hostname
verification, account-scoped credential selection, JMS authentication timing,
exact Topic, selector, `noLocal`, durability, client ID, acknowledgment, capture,
publishing, retry, fallback, or Production behavior. XML parsing remains out of
scope.

## Verification

Deterministic fake-JMS tests cover startup ordering, partial-startup failures,
normal and deadline-bounded shutdown, shutdown requests during idle and active
capture, idempotence, stale generations, asynchronous JMS failure, illegal
transitions, close ordering, and the absence of automatic reconnect.
