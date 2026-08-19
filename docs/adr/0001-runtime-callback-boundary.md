# ADR 0001: Runtime Callback Boundary

## Status

Accepted as the original protocol-neutral design; superseded for the managed
Java Scenario receiver by ADRs 0005, 0006, and 0010.

## Implementation update — 2026-08-19

The verified Java receiver cannot return immediately after creating an
envelope because its established lossless boundary is stricter: the admitted
JMS callback durably captures the native payload, acknowledges only after that
commit, and then performs bounded interpretation. No JMS object crosses into
the application envelope. This update preserves the historical rationale while
recording the later protocol-specific decision.

## Context

The native USGS transport and client library have not been verified. A client
may invoke callbacks on a library-owned I/O thread, where blocking or domain
work could disrupt transport processing. The runtime therefore needs a narrow,
transport-neutral handoff boundary.

## Decision

A transport callback creates a `MessageEnvelope`, submits it to the runtime
handoff, and returns immediately. No parsing, routing, storage, retry, domain
processing, or publishing occurs in the callback.

## Rationale

A minimal callback isolates transport I/O from application work, avoids
embedding unverified protocol behavior in domain components, and creates a
clear boundary for later testing. Excluding publishing also preserves the
passive laboratory constraint.

## Alternatives considered

- Parse and route in the callback. Rejected because this could block a
  transport-owned thread and couple transport to message semantics.
- Persist in the callback. Rejected because storage latency and failure behavior
  must not be imposed before protocol behavior is verified.
- Apply retry or acknowledgment policy in the callback. Deferred because the
  native delivery semantics are not confirmed.

## Consequences

- The runtime needs a handoff that accepts `MessageEnvelope` instances.
- The callback cannot wait for downstream parsing, routing, or persistence.
- Backpressure and saturation must be represented outside domain logic.
- Callback completion does not mean safe persistence or successful processing.

## Deferred protocol-dependent questions

- Whether callback return implies acknowledgment
- Whether explicit acknowledgment must occur on the callback thread
- Whether submission may block briefly or must be strictly non-blocking
- Which native headers and identifiers belong in the envelope
- How transport-level rejection is represented
- How callbacks behave during transport shutdown

## Acceptance criteria

- The callback creates one envelope and invokes the runtime handoff.
- It contains no parsing, routing, storage, retry, domain processing, or
  publishing behavior.
- Its prompt return can be tested without waiting for downstream completion.
- No acknowledgment behavior is implemented without protocol evidence.
