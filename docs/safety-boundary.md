# Safety Boundary

> **SHAKEALERT LAB — NO OPERATIONAL OUTPUTS**

Phase 0/1 is development-only: repository structure, governance, templates,
non-privileged scripts, host observation, and local validation. It does not
connect externally, receive or replay messages, inspect credentials, implement
a transport, alter CUBE/PX-01, publish data, install host services, or control
physical/emergency systems.

All future application entry points must fail closed through the mandatory
interlock. A missing `ALLOW_OPERATIONAL_OUTPUTS` value, or any value other than
literal `false`, prevents startup. Future messages with conflicting or
insufficient environment evidence must be classified `UNKNOWN` and must never
be promoted to an operational pathway.

Stop work if an operational pathway is discovered, a secret appears in source
or evidence, the target environment is ambiguous, time is unsynchronized, or
authorization cannot be demonstrated.

