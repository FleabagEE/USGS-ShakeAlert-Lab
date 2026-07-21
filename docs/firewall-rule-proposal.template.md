# Firewall Rule Proposal Template

This document proposes policy only; it does not install or modify rules.

- Deny unsolicited inbound traffic by default.
- Permit DNS only to approved resolvers and time traffic only to approved
  synchronization sources.
- Permit administrative and package-update traffic only through approved
  enterprise paths.
- Add production and scenario egress separately after endpoint, port, protocol,
  VPN/proxy, SNI, address-change, and allow-list requirements are verified.
- Prohibit wildcard broker access, broad service egress, inbound messaging
  ports, and CUBE/customer-network routes.
- Require peer review, change record, test evidence, and rollback instructions.

Decision: **NO-GO TO APPLY ENDPOINT RULES** until separately approved discovery
provides verified inputs.

