# ADR 0004: Runtime Startup, Shutdown, and Failure

## Status

Accepted

## Implementation update — 2026-08-19

ADRs 0005, 0007, 0008, 0010, and 0011 implement the managed Java lifecycle,
30-second callback-drain deadline, SIGTERM coordination, exit semantics,
capture/ACK failure policy, and sanitized asynchronous JMS failure handling.
The final `cd8e55c` live acceptance verified nine delivery cycles and ordered
shutdown through `STOPPED` with exit status 0. No automatic in-process or
systemd restart is enabled.

## Context

The runtime must not accept deliveries before downstream processing is ready,
and it must stop accepting new work before draining accepted messages.
Expected message errors should not unnecessarily terminate the service, while
programming defects and worker failure must remain visible and must not be
hidden by automatic in-process restart.

## Decision

Start downstream components and the consumer worker before starting the
transport. During shutdown, stop the transport before draining accepted work.
Use a draining shutdown with a configurable deadline. `stop()` must be
idempotent. Forced shutdown must report queued and in-progress work.

Expected message-level errors may be isolated so processing can continue.
Unexpected programming errors or worker failure transition the runtime to
`FAILED`. Do not automatically restart the worker inside the process. Do not
hide programming defects with overly broad exception handling.

## Rationale

Ordered startup prevents deliveries from arriving without a ready consumer.
Ordered shutdown establishes a clear acceptance boundary before draining.
Idempotence makes repeated stop requests safe. Separating expected input errors
from unexpected failures preserves availability without concealing defects or
creating uncontrolled duplicate processing.

## Alternatives considered

- Start transport before the worker. Rejected because deliveries could arrive
  without a ready downstream path.
- Stop the worker before transport. Rejected because callbacks could submit work
  with no consumer.
- Exit immediately without draining. Rejected because accepted work could be
  silently abandoned.
- Drain indefinitely. Rejected because shutdown could hang permanently.
- Automatically restart a failed worker. Rejected because acknowledgment,
  duplicate, and in-progress consequences are unknown.
- Catch every exception and continue. Rejected because programming defects
  would be hidden.

## Consequences

- Startup and shutdown require explicit lifecycle coordination.
- Transport starts only after downstream readiness.
- Transport stop establishes the boundary after which no new work is accepted.
- Shutdown tracks queued and in-progress work until completion or deadline.
- Repeated `stop()` calls have no additional harmful effect.
- Expected message errors require an explicit, narrow classification.
- Unexpected worker termination is a service-level failure.
- External service management will own any later approved process restart.

## Deferred protocol-dependent questions

- Ordering of transport stop, unsubscribe, and disconnect
- Whether callbacks continue after transport stop returns
- Treatment of unacknowledged queued or in-progress messages
- The shutdown deadline value
- Whether shutdown calls must run on a particular thread
- Which message-level errors are safe to isolate
- Exit status and external restart behavior after `FAILED`
- Behavior when storage blocks during draining shutdown

## Acceptance criteria

- Downstream components and the worker are ready before transport start.
- Transport stop occurs before accepted work is drained.
- Shutdown uses a configurable deadline.
- Repeated `stop()` calls are safe and idempotent.
- Forced shutdown reports queued and in-progress work.
- Expected message-level failures use explicit exception categories.
- Programming errors and worker failure transition the runtime to `FAILED`.
- The runtime does not automatically restart a failed worker.
- Exception handling does not broadly suppress programming defects.
