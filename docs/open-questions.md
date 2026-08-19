# Open Questions

Scenario DNS, TCP, public TLS, hostname verification, ActiveMQ OpenWire,
`QuakeLogic-SA1` authentication, exact Topic subscription, Event delivery,
bounded native capture, post-capture `CLIENT_ACKNOWLEDGE`, bounded parsing, and
managed shutdown are verified. The initial Westmoreland proof-of-concept
delivered eight updates. Final application-revision acceptance at `cd8e55c`
delivered nine messages; all nine capture integrity checks passed, all nine
acknowledgements followed durable capture, and parsing produced eight Event
updates plus one follow-up.

## Repository verification status

Maven `3.8.7`, Java 21, and the POM-pinned dependencies/plugins were verified
using the isolated `.mvn/repository`. The current 97-test JUnit suite, checksum
validation, dependency convergence, upper-bound and duplicate-class checks,
packaging, runtime guards, and two-build reproducibility passed. One
historical-corpus test is opt-in when approved local sources are not supplied;
the frozen corpus has separately verified 28/28 members.

## Remaining interface questions

The application acknowledgement boundary is implemented and verified, but
broker confirmation timing remains open because ActiveMQ asynchronous ACK
sending is unchanged. Also open are mTLS policy, broker redelivery windows,
heartbeat/inactivity and long-duration keepalive behavior, prefetch/flow
control, missed-message behavior for non-durable consumers, authoritative
schema and complete sequence meanings, cancellation behavior, expected
sizes/rates, allow-list/VPN/proxy requirements, credential expiration/rotation,
support escalation, and capture/evidence/log retention operations.

All Production endpoint facts, credentials, destinations, protocol decisions,
and authorization remain open. No Production inference may be made from the
successful Scenario proof-of-concept.
