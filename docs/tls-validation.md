# TLS and Authentication Validation Procedure

Do not run until the endpoint, scope, and connection are authorized. Record UTC
timestamp, approved hostname/port, DNS result, TCP result, chain, hostname
match, expiration, issuer, fingerprint, negotiated TLS/protocol version,
cipher, mTLS requirement, and sanitized authentication result. Never disable
verification or use wildcard subscriptions. Classify failures as DNS, routing,
firewall, certificate, hostname, client certificate, authentication,
authorization, subscription, protocol/version, disabled account, allow-list,
unavailable server, or unknown.

## Scenario endpoint validation — 2026-08-06

- Checked initially at: `2026-08-06T16:14:37Z`
- TLS revalidated before the latest authorized authentication attempt
- Endpoint: `scenario.eew.shakealert.org:61617`
- DNS: success (`131.215.68.97` observed during initial validation)
- TCP: success
- TLS verification: success using the system trust store; no insecure bypass
- Hostname verification: success
- Certificate chain: valid, four certificates presented, verify return code 0
- Leaf validity: `2026-06-14T19:05:12Z` through `2026-09-12T19:05:11Z`
- Leaf issuer: Let's Encrypt YE1
- Leaf SHA-256: `B2:25:27:06:54:10:06:72:A8:73:66:76:27:B0:BE:09:8E:86:66:A2:5B:09:B1:50:C4:45:A8:1B:F4:7F:D5:E7`
- Negotiated TLS: TLS 1.3, `TLS_AES_256_GCM_SHA384`
- Application protocol evidence: server-initiated ActiveMQ OpenWire preface, wire-format version 12
- Authentication: reached but unsuccessful with protected, manually entered credentials
- Sanitized broker result: username or password is invalid
- Subscription: not established
- Live messages: none received or captured
- Operational output: none; no publishing occurred
- Current blocker: awaiting USGS confirmation of broker credentials and Scenario account authorization
- Previous certificate comparison: prior expired leaf is unavailable in repository evidence and could not be independently recovered from public CT history; the new leaf has a new validity interval and currently validates successfully
