# Protocol Decision

Decision: **SCENARIO TRANSPORT VERIFIED; LIVE INTEGRATION BLOCKED AT
AUTHENTICATION**.

Authorized evidence confirms ActiveMQ OpenWire over verified TLS for the
Scenario endpoint at `scenario.eew.shakealert.org:61617`; broker wire-format
version 12 was observed. The passive Java receiver is restricted to the exact
Event topic `eew.test_QuakeLogic-SA1.dm.data`, uses a non-durable consumer,
and has no publishing path.

Broker authentication is reached but rejected with a sanitized invalid
username-or-password reason. Subscription behavior, acknowledgments, message
schema, lifecycle, cancellation, sequencing, health monitoring, and
missed-message behavior remain unverified because no subscription or live
capture has succeeded. Production protocol and any equality with Scenario
remain **UNKNOWN**. The offline MQTT adapter is not evidence of USGS
compatibility.
