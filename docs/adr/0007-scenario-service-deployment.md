# ADR 0007: Repository-managed Scenario service and local status

## Status

Accepted, installed, and validated for the authorized Scenario scope. Every
future start remains separately authorized.

## Decision

Use one foreground systemd service with `Restart=no`, no PID file, and the repository runtime guard. systemd creates volatile runtime and persistent state directories. Java atomically publishes a fixed sanitized health schema and bounded expected-rejection records. Readiness is stricter than liveness. Parser `FAILED` disables readiness but does not stop native capture or restart parsing.

Duplicate detection remains activation-local until bounded transactional persistence is separately approved.

## Consequences

No broker retry or process restart is automatic. Stop timeout exceeds the internal drain deadline, and normal timeout handling does not use SIGKILL. Deployment currently uses `quakelogic` to preserve the credential-owner invariant.
