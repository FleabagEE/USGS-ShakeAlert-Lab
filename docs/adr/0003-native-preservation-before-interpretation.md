# ADR 0003: Native Preservation Before Interpretation

## Status

Accepted

## Implementation update — 2026-08-19

ADR 0010 resolves the deferred acknowledgement boundary for the managed Java
receiver: file fsync, atomic move, and capture-directory fsync complete before
`Message.acknowledge()`, and parsing follows acknowledgement. Capture or ACK
failure latches `FAILED`; parser rejection cannot invalidate the capture.
Broker confirmation/redelivery semantics remain external questions.

## Context

The laboratory must capture and characterize the authorized native ShakeAlert
interface without guessing about message structure. Parsing, normalization,
and interpretation can fail or evolve, but those outcomes must not destroy the
original evidence. Unknown topics and malformed payloads are important for
protocol and schema discovery.

## Decision

Preserve the native payload and required transport metadata before parsing,
normalization, or domain interpretation. Unknown topics and malformed payloads
must remain preservable. Native capture must not depend on parser success.
Exact acknowledgment timing remains deferred until the native protocol is
verified.

## Rationale

Preserving first provides an auditable source record, supports later parser and
schema corrections, and prevents information loss when input is unknown or
invalid. It implements the governing principle: preserve first; interpret
second.

## Alternatives considered

- Parse before capture and store only valid messages. Rejected because malformed
  and unknown messages would be lost.
- Store only normalized records. Rejected because normalization cannot replace
  the native source and may omit information.
- Drop unknown topics. Rejected because discovery and authorization
  discrepancies require evidence.
- Acknowledge immediately after enqueue. Deferred because an in-memory handoff
  is not durable preservation and protocol semantics are unknown.

## Consequences

- Native capture is a prerequisite to parser-dependent processing.
- Parser errors do not erase or replace native records.
- Unknown-topic handling must permit preservation without a known domain route.
- Storage must eventually retain exact payloads and verified transport metadata
  without credential leakage.
- Normalized records must retain provenance to their native capture.
- Capture success and acknowledgment readiness cannot be treated as equivalent
  until the protocol is verified.

## Deferred protocol-dependent questions

- Exact metadata and headers required for lossless capture
- Broker identifiers, timestamps, and delivery sequence semantics
- Redelivery and acknowledgment behavior
- Whether acknowledgment occurs only after durable storage
- Maximum payload size and binary-payload handling
- Storage behavior for an unknown destination
- Confidentiality restrictions on captured payloads and headers

## Acceptance criteria

- Native payload bytes are preserved without successful decoding or parsing.
- Required verified transport metadata is preserved with the payload.
- Unknown topics and malformed payloads remain preservable.
- Parsing and normalization occur only after native preservation.
- Parser failure cannot delete or overwrite a native capture.
- Acknowledgment timing is not selected without protocol evidence.
