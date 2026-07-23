# ADR 0002: Bounded FIFO Queue and Single Worker

## Status

Accepted

## Context

Transport receipt must be separated from downstream processing while retaining
predictable local ordering and bounded resource use. Message rates, maximum
payload sizes, delivery guarantees, and acknowledgment semantics are not yet
verified.

## Decision

Use one configurable bounded in-memory FIFO queue and one consumer worker
initially. Preserve local enqueue order. Do not silently evict messages,
reorder them by priority, or automatically deduplicate them. Queue depth and
saturation must be observable.

Queue saturation produces an explicit degraded or critical runtime condition.
Messages must not be silently discarded. Final rejection, blocking,
disconnect, and acknowledgment behavior is protocol-dependent and deferred.

## Rationale

A bounded queue prevents uncontrolled memory growth. FIFO behavior and one
consumer provide a simple, auditable ordering model while protocol ordering and
observed throughput remain unknown. Explicit saturation reporting keeps
overload visible without inventing transport behavior.

## Alternatives considered

- An unbounded queue. Rejected because sustained load or downstream failure
  could exhaust memory.
- Multiple workers. Deferred because they could reorder related updates and are
  not justified by measured throughput.
- Priority processing. Rejected because it changes local enqueue order.
- Silent oldest-message or newest-message eviction. Rejected because it creates
  an incomplete native record without an explicit failure.
- Automatic deduplication. Rejected because duplicates and redeliveries are
  required evidence.
- A durable queue. Deferred until delivery and storage requirements are known.

## Consequences

- Queue capacity must be configurable.
- The initial worker processes one accepted envelope at a time.
- Queue depth, saturation events, and worker activity require observable state.
- Enqueue order does not prove broker or event order.
- Enqueue success does not mean durable preservation.
- Concurrency remains limited until measurement justifies a change.

## Deferred protocol-dependent questions

- Queue capacity and any enqueue timeout
- Whether saturation blocks, rejects, or causes disconnect
- Whether rejection leaves a delivery unacknowledged
- Whether the transport can pause delivery
- Whether callback return creates implicit acknowledgment
- Whether a durable spool is required
- Whether measured throughput requires partitioned concurrency

## Acceptance criteria

- Queue capacity is configurable and bounded.
- Accepted envelopes leave the queue in local FIFO order.
- Exactly one consumer worker is used initially.
- No path silently evicts, reorders, or deduplicates messages.
- Queue depth and saturation are observable.
- Saturation produces an explicit degraded or critical condition.
- Protocol-dependent saturation behavior remains deferred until verified.
