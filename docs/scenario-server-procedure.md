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

## Historical proof-of-concept checkpoint

On 2026-08-18, an already connected listener received eight Event updates from
the authorized M4.6 Westmoreland Event-only Scenario. Eight native captures
completed, zero temporary captures remained, and every decoded payload size
and SHA-256 matched its stored metadata. No JMS, transport, capture,
publishing, fallback, or Production error occurred during the successful test.
Sanitized evidence was preserved locally.

## Final managed-receiver acceptance

On 2026-08-19, installed application revision `cd8e55c` was `READY` before the
operator requested an authorized M4.6 Westmoreland Event-only Scenario through
the external Scenario portal. Portal Event ID `18718` is only a portal-side
correlation identifier; it is not asserted to match a JMS message identity.

The receiver processed nine deliveries as nine durable captures followed by
nine application acknowledgements and parser processing. Eight parsed as
`ShakeAlertEventUpdate` and one as `ShakeAlertFollowUp`. Integrity passed for
all captures, no rejection or failure occurred, and ordered systemd shutdown
exited 0 with no remaining process or broker socket.

No further Scenario event is required for this milestone. The current 97-test
JUnit suite, historical 28-member corpus, dependency and duplicate-class
analysis, package inspection, guarded runtime verification, checksum
validation, and two-build reproducibility have passed. Every future Scenario
connection or portal request still requires separate authorization.
