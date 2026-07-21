# Phase 0/1 Risk Register

Likelihood (L) and impact (I): Low, Medium, High.

| ID | Risk | L | I | Controls and response | Owner role |
|---|---|---:|---:|---|---|
| R01 | Test data treated as live | M | H | Separate future config/storage; multi-source classification; conflict becomes UNKNOWN and is quarantined | Application lead |
| R02 | Credential exposure | M | H | Ignored 0700 directory, protected-file templates, no CLI secrets; rotate and review if exposed | Security |
| R03 | Unauthorized publishing | L | H | No transport/publisher in Phase 0/1; future least-privilege read-only authorization | Safety lead |
| R04 | Connection to wrong endpoint | M | H | No endpoints configured; later verified matrix and peer review | Network lead |
| R05 | Clock drift | M | H | Chrony evidence; 100 ms target, 250 ms warning, 1 s critical pending authoritative requirement | Operations |
| R06 | Historical replay treated as current | M | H | No replay now; future REPLAY classification and loopback-only default | QA |
| R07 | Duplicate messages | H | M | Preserve native capture; future identifier/hash/version tracking | Application lead |
| R08 | Out-of-order updates | M | H | Future version state machine; never overwrite capture | Application lead |
| R09 | Cancellation mishandled | M | H | Verify complete scenario lifecycle; do not infer undocumented semantics | Application lead |
| R10 | Connection loss | H | M | Future heartbeat and bounded backoff with jitter | Operations |
| R11 | Incorrect environment classification | M | H | Endpoint/header/payload evidence; conflict is UNKNOWN | Security |
| R12 | Excessive or secret-bearing logs | M | H | Minimal structured logging, retention limits, secret review | Security |
| R13 | Disk exhaustion/write failure | M | H | Future capacity alarms, atomic writes, bounded payload size | Operations |
| R14 | Dependency vulnerabilities | M | M | Minimal baseline; protocol packages deferred; inventory/review | Security |
| R15 | Restart loop | M | M | Future rate limits and degraded state; no services now | Operations |
| R16 | Accidental CUBE triggering | L | H | No CUBE route, code, credential, topic, or configuration | Safety lead |
| R17 | Credential retirement failure | L | H | Future approved rotation/retirement procedure and audit trail | Security |
| R18 | Public dashboard exposure | L | H | No dashboard now; future loopback-only default | Security |

Residual risk is unacceptable for external connectivity because endpoint,
credential, protocol, and authorization discovery is outside the approved
scope. The current decision is NO-GO beyond Phase 1.

