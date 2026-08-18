# Open Questions

Scenario DNS, TCP, public TLS, hostname verification, ActiveMQ OpenWire,
`QuakeLogic-SA1` authentication, exact Topic subscription, Event delivery, and
bounded native capture are verified. The M4.6 Westmoreland Scenario delivered
eight updates and all eight capture integrity checks passed.

## Repository verification status

Maven `3.8.7`, Java 21, and the POM-pinned dependencies/plugins were verified
using the isolated `.mvn/repository`. All ten JUnit tests, checksum validation,
dependency convergence, upper-bound and duplicate-class checks, packaging,
runtime guards, and two-build reproducibility passed.

## Remaining interface questions

Still open are mTLS policy, acknowledgment/redelivery semantics, heartbeat and
long-duration keepalive behavior, missed-message behavior for nondurable
consumers, authoritative schema and sequence meanings, cancellation behavior,
expected sizes/rates, allow-list/VPN/proxy requirements, credential expiration,
support escalation, and evidence/log retention policy.

All Production endpoint facts, credentials, destinations, protocol decisions,
and authorization remain open. No Production inference may be made from the
successful Scenario proof-of-concept.
