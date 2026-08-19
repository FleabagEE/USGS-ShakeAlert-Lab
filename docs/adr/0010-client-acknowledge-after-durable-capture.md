# ADR 0010: Acknowledge only after durable native capture

## Status

Accepted for offline implementation; installation and receiver restart remain separately authorized.

## Decision

The non-transacted JMS session remains `CLIENT_ACKNOWLEDGE`. Each admitted delivery follows this order:

`MESSAGE_CALLBACK -> durable native capture -> CAPTURE_COMMITTED -> ACKNOWLEDGEMENT_STARTED -> Message.acknowledge() -> ACKNOWLEDGED -> MessageEnvelope -> domain parsing`.

A `NativeCaptureCommit` is an immutable, application-owned, JMS-free result. It exists only after payload write, file fsync, atomic move, and capture-directory fsync complete. `MessageEnvelope` is constructed from that result only after acknowledgement succeeds.

`CLIENT_ACKNOWLEDGE` is cumulative for deliveries on one session. A capture failure therefore closes callback admission, latches the service `FAILED`, wakes the coordinator, and prevents later delivery. An acknowledgement failure preserves the committed capture, emits `ACKNOWLEDGEMENT_FAILED`, latches `FAILED`, and is not retried in-process. Ordered coordinator teardown remains consumer, admitted-callback drain, session, connection, and instance lock. Parser acceptance never controls acknowledgement: expected rejection remains nonterminal after ACK, while unexpected parser failure follows the existing parser-failure policy.

Every redelivery is captured again and acknowledged only after its new capture commits. Activation-local duplicate domain processing continues to use JMS identity when present and Event/update identity plus payload SHA-256 otherwise. Persistent deduplication remains out of scope.

## ActiveMQ 5.19.10 transport detail

ActiveMQ Classic 5.19.10 defaults `sendAcksAsync` to true. This milestone does not change that transport option. The application has a precise, exactly-once invocation boundary, but successful return from `Message.acknowledge()` can mean the ACK was accepted by the client transport before broker confirmation. No supported bounded synchronous-ACK timeout was established that is compatible with the existing 30-second callback/shutdown deadline, so switching to synchronous sends could introduce an unbounded callback stall. Durable capture and deterministic redelivery handling make the residual uncertainty lossless; a future transport change requires a separately proven bounded timeout.

## Consequences

Health adds only sanitized `messages_acknowledged` and `acknowledgement_failures`. Lifecycle output adds `ACKNOWLEDGEMENT_STARTED`, `ACKNOWLEDGED`, and `ACKNOWLEDGEMENT_FAILED`; it never includes JMS IDs, payloads, headers, credentials, or raw exception text. Endpoint, Topic, TLS, authentication, publishing isolation, retry/fallback policy, capture format, and parser profiles do not change.
