# Endpoint Matrix

Only the Scenario facts explicitly marked verified below are confirmed.
Production remains untested and unauthorized.

| Attribute | Production | Scenario |
|---|---|---|
| Logical environment | Configured identity only; untested | Scenario |
| Host/port | REQUIRED FROM USGS | `scenario.eew.shakealert.org:61612`, verified |
| Protocol/version | REQUIRED FROM USGS | ActiveMQ OpenWire over TLS; broker wire-format version 12 observed |
| TLS/hostname | REQUIRED FROM USGS | Public CA chain and hostname verification succeeded; TLS 1.3 observed |
| Authentication | REQUIRED FROM USGS | `QuakeLogic-SA1`; real authentication succeeded through JMS session creation |
| Destination type/name | REQUIRED FROM USGS | Non-durable Topic `eew.test_QuakeLogic-SA1.dm.data`; exact name verified; no selector; `noLocal=false` |
| Delivery evidence | None | Initial proof-of-concept delivered 8 updates; final `cd8e55c` acceptance delivered 9 messages (8 Event updates, 1 follow-up) |
| Capture evidence | None | Final acceptance: 9 completed, 0 temporary, 0 failures; payload sizes and SHA-256 values verified |
| Publishing/fallback | None authorized | No publishing, wildcard, retry, or fallback path used |
| Acknowledgment | REQUIRED FROM USGS | `CLIENT_ACKNOWLEDGE` session; each delivery is acknowledged exactly once by the application after durable native capture commit and before interpretation |
| Reconnect/keepalive | REQUIRED FROM USGS | Automatic retry/fallback disabled; one earlier listener later reported an inactivity exception and was replaced under authorization |
| mTLS/client ID | REQUIRED FROM USGS | No client ID; mTLS requirement not established |
| Encoding/schema/rate | REQUIRED FROM USGS | Bounded observed Event, finite-fault, and follow-up profiles implemented; broader schema and rate characterization remains open |
