# Scenario Server Procedure

## Verified boundary

- Host/port: `scenario.eew.shakealert.org:61612`
- Protocol: ActiveMQ OpenWire over hostname-verified TLS
- Account: `QuakeLogic-SA1`
- Destination: Topic `eew.test_QuakeLogic-SA1.dm.data`
- Consumer: non-durable, selector `null`, `noLocal=false`, no client ID
- Publishing/retry/fallback: prohibited and absent

Account identity and destination are independent authoritative facts. Account
selection derives only `credentials/scenario/<account-id>/username` and
`password`; it must never accept an independent credential directory or legacy
fallback. The destination must be supplied exactly and must never be derived,
normalized, wildcarded, or converted to a Queue.

## Required lifecycle

1. Verify the approved Scenario endpoint and TLS hostname.
2. Create the OpenWire connection with username, password, then broker URL.
3. Create the JMS session to force real authentication.
4. Create exactly one Topic consumer with no selector and `noLocal=false`.
5. Install the `MessageListener` before `connection.start()`.
6. Report success only after session creation and `connection.start()` succeed.
7. Emit `MESSAGE_CALLBACK` before inspecting or validating the body.
8. Commit a bounded, atomic native capture before any later interpretation.
9. Never publish, retry, fall back, or connect to Production.

## Successful integration checkpoint

On 2026-08-18, an already connected listener received eight Event updates from
the authorized M4.6 Westmoreland Event-only Scenario. Eight native captures
completed, zero temporary captures remained, and every decoded payload size
and SHA-256 matched its stored metadata. No JMS, transport, capture,
publishing, fallback, or Production error occurred during the successful test.
Sanitized evidence was preserved locally.

No further Scenario event is required for repository build verification. Offline
Java compilation, all ten JUnit tests, dependency and duplicate-class analysis,
package inspection, guarded runtime verification, checksum validation, and
two-build reproducibility have passed.
