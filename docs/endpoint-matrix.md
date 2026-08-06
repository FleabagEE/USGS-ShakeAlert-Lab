# Endpoint Matrix

Only the Scenario facts explicitly marked verified below are confirmed.
Remaining cells require authoritative USGS clarification or later authorized
evidence.

| Attribute | Production | Scenario |
|---|---|---|
| Logical name/environment | production (configured identity only) | scenario (configured identity only) |
| Host/IP/port | REQUIRED FROM USGS | `scenario.eew.shakealert.org:61617` — DNS and TCP verified 2026-08-06 |
| Protocol/version | REQUIRED FROM USGS | ActiveMQ OpenWire, broker wire-format version 12 observed |
| TLS/CA/mTLS/SNI | REQUIRED FROM USGS | Public CA chain and hostname verified; TLS 1.3 observed; mTLS requirement not established |
| Authentication/client ID | REQUIRED FROM USGS | Authentication reached; rejected with sanitized invalid username-or-password reason; broker credentials/account authorization require USGS confirmation |
| Destination/topic/queue/vhost | REQUIRED FROM USGS | Exact Event topic assigned as `eew.test_QuakeLogic-SA1.dm.data`; subscription not yet established |
| Durable subscription/ack/QoS | REQUIRED FROM USGS | Non-durable JMS consumer and client acknowledgment selected locally; broker behavior not yet verified |
| Heartbeat/keepalive/reconnect | REQUIRED FROM USGS | REQUIRED FROM USGS |
| Environment marker | REQUIRED FROM USGS | REQUIRED FROM USGS |
| Encoding/content type/max size | REQUIRED FROM USGS | REQUIRED FROM USGS |
| Rate/allow-list/VPN/proxy | REQUIRED FROM USGS | REQUIRED FROM USGS |
| Expiration/support contact | REQUIRED FROM USGS | REQUIRED FROM USGS |
