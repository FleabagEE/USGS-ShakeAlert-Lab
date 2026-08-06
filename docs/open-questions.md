# Open Questions

Scenario DNS, TCP, public TLS validation, hostname validation, and ActiveMQ
OpenWire negotiation are verified. The assigned exact Event topic is known, but
no subscription has succeeded.

The immediate blocker is authoritative confirmation from USGS of:

- the correct ActiveMQ broker credentials for QuakeLogic-SA1;
- whether the Scenario account is enabled and authorized for the assigned topic;
- whether web-portal and broker credentials are identical for this account.

Still open are mTLS requirements, client ID policy, acknowledgment and durable
behavior, heartbeat, keepalive, reconnect, missed messages, environment
markers, encoding, schema, cancellation, sequence, sizes, rates, allow-list,
VPN/proxy requirements, credential expiration, and support escalation. All
Production endpoint facts and authorization remain open. Operational questions
include evidence retention, named approvals, log retention, certificate
renewal, scenario scheduling, and later acceptance gates.
