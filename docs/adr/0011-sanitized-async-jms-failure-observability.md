# ADR 0011: Sanitized Asynchronous JMS Failure Observability

## Decision

An asynchronous JMS failure is classified only from allowlisted Java exception
types found through at most eight identity-distinct cause or JMS-linked-exception
nodes. Exception messages, stack traces, broker headers, filesystem paths, and
arbitrary nested text are never inspected or persisted. Supported categories
are `INACTIVITY_TIMEOUT`, `TRANSPORT_EOF`, `TRANSPORT_TIMEOUT`,
`TLS_TRANSPORT_FAILURE`, `BROKER_SECURITY_FAILURE`,
`JMS_CONNECTION_FAILURE`, and `UNKNOWN_JMS_FAILURE`.

`TRANSPORT_RESET` is intentionally not claimed: the installed type hierarchy
exposes a generic `SocketException`, while distinguishing reset from other
socket failures would require parsing implementation-dependent message text.
Such cases fail closed as `UNKNOWN_JMS_FAILURE` or, when wrapped by a structured
ActiveMQ connection exception, `JMS_CONNECTION_FAILURE`.

The exception object remains on the ExceptionListener call stack. The service
copies only allowlisted values into an immutable diagnostic, latches `FAILED`,
closes callback admission, emits sanitized `ASYNC_EXCEPTION` and `FAILED`
lifecycle events, requests coordinator teardown, and never reconnects.

## Persistent record

The latest incident is atomically replaced at
`/var/lib/shakealert-scenario-receiver/incidents/async-jms-latest.json`. The
directory is `0750`, the file is `0640`, and the UTF-8 record is limited to
4,096 bytes. Publication is temporary file, file fsync, atomic replacement,
mode reinforcement, and directory fsync. It records only failure UTC, state at
failure, connection uptime, approved account/endpoint/destination identities,
the category, delivery/capture/acknowledgement/callback counters, and whether
shutdown was already requested.

Diagnostic persistence is best-effort observability. Failure to write it must
not prevent the original fail-closed transition or ordered coordinator
teardown. The terminal process category preserves the classified runtime cause
instead of relabeling it as startup failure.

## Non-goals

This decision changes no endpoint, TLS/OpenWire option, inactivity threshold,
subscription, acknowledgement boundary, capture format, parser profile,
restart policy, retry/failover behavior, publishing behavior, or Production
isolation.
