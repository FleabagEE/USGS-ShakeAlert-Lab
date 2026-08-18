# ADR 0006: MessageEnvelope and safe Event parsing

## Status

Accepted for offline implementation; receiver launch remains separately authorized.

## Decision

The callback path is ordered and one-way:

`JMS Message -> durable native capture -> MessageEnvelope -> bounded XML validation -> supported Event parser -> ShakeAlertEventUpdate`.

`MessageEnvelope` owns a defensive payload copy and derived byte count and SHA-256. It contains only allowlisted delivery and provenance values. It cannot retain a JMS or ActiveMQ object. Native capture commit completes—including file and directory durability—before an envelope exists or parsing begins.

The supported XML profile is the observed Scenario Event profile:

- exact root `event_message` with no namespace;
- `message_type` equal to `new` or `update`;
- observed algorithm/schema discriminator `alg_vers="2.3.23 2020-04-01"`;
- numeric root `version` as the Event update version;
- required `core_info` and `contributors`;
- optional `gm_info`, absent from early and present in later updates.

The parser rejects invalid UTF-8 and BOM input, DTDs, entities, external DTD/schema access, XInclude, unsupported roots and schemas, and configured size/element/depth/attribute/text limits. It has no network resolver.

Expected data failures preserve the capture and allow subsequent deliveries. Unexpected parser/programming failures latch the parser processor in `FAILED`; it does not restart itself. Duplicate domain processing is suppressed when either a previously accepted non-null JMS message ID repeats, or the tuple `(Event update identity, payload SHA-256)` repeats. Every delivery remains captured.

## Consequences

Transport, subscription, acknowledgment, retry, publishing, and Production-isolation behavior is unchanged. XML parsing never receives the live JMS object. Service-manager deployment and externally published parser health remain future work.
