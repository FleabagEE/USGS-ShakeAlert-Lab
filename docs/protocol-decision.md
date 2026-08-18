# Protocol Decision

Decision: **SCENARIO OPENWIRE/TLS END-TO-END PROOF-OF-CONCEPT VERIFIED**.

The authoritative Scenario transport is ActiveMQ OpenWire over
hostname-verified TLS at `scenario.eew.shakealert.org:61612`. Broker
wire-format version 12 was observed. The explicit account is
`QuakeLogic-SA1`; real authentication is accepted only after JMS session
creation succeeds.

The verified destination is the exact non-durable Topic
`eew.test_QuakeLogic-SA1.dm.data`, with no selector, `noLocal=false`, and no
client ID. The receiver rejects wildcard and non-Event destinations, derives
credentials only from the selected account directory, and contains no Queue,
publishing, retry, fallback, or Production pathway.

During the authorized M4.6 Westmoreland Event-only Scenario, the active
consumer received eight updates and committed eight bounded native captures.
All captured payload sizes and SHA-256 values verified. No JMS, transport,
capture, publishing, fallback, or Production error occurred during that test.

This decision applies only to Scenario reception. The repository-defined Java
21/Maven build, JUnit suite, dependency controls, runtime guards, checksum
manifest, and reproducible packaging are verified. Production protocol and any
equality with Scenario remain **UNKNOWN**.
