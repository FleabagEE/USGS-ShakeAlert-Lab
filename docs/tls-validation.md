# TLS and Authentication Validation

Never disable certificate or hostname verification. Record only sanitized
endpoint, certificate, protocol, and lifecycle evidence; never record
credential values.

## Scenario endpoint

- Initial validation: `2026-08-06T16:14:37Z`
- End-to-end Scenario validation completed: 2026-08-18
- Endpoint: `scenario.eew.shakealert.org:61612`
- DNS/TCP: successful
- TLS verification: successful using the system trust store; no insecure bypass
- Hostname verification: successful
- Certificate chain: valid, four certificates presented during initial validation
- Leaf validity observed: `2026-06-14T19:05:12Z` through `2026-09-12T19:05:11Z`
- Leaf issuer observed: Let's Encrypt YE1
- Leaf SHA-256 observed: `B2:25:27:06:54:10:06:72:A8:73:66:76:27:B0:BE:09:8E:86:66:A2:5B:09:B1:50:C4:45:A8:1B:F4:7F:D5:E7`
- Negotiated TLS observed: TLS 1.3, `TLS_AES_256_GCM_SHA384`
- Application protocol: ActiveMQ OpenWire; broker wire-format version 12
- Authentication: successful for `QuakeLogic-SA1` through JMS session creation
- Connection start: successful
- Subscription: exact non-durable Topic `eew.test_QuakeLogic-SA1.dm.data`
- Delivery: eight M4.6 Westmoreland Event updates received
- Capture: eight completed, zero temporary, all payload size/SHA-256 checks passed
- Errors during successful test: no JMS, transport, or capture errors
- Safety: no publishing, fallback, wildcard, Production, or CUBE/PX-01 activity

The certificate observations above describe the recorded validation interval;
future connections must revalidate the current certificate and hostname rather
than relying on historical fingerprint or validity data.
