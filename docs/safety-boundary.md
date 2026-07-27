# Safety Boundary

> **SHAKEALERT LAB — NO OPERATIONAL OUTPUTS**

The laboratory includes a deployed credential-independent framework and inert services. It does not connect externally, activate a USGS transport, inspect secret values during configuration, alter CUBE/PX-01, publish data, or control physical/emergency systems. Native capture, validation, normalization, and replay operate only on supplied local data until endpoint access is authorized.

All future application entry points must fail closed through the mandatory
interlock. A missing `ALLOW_OPERATIONAL_OUTPUTS` value, or any value other than
literal `false`, prevents startup. Future messages with conflicting or
insufficient environment evidence must be classified `UNKNOWN` and must never
be promoted to an operational pathway.

Stop work if an operational pathway is discovered, a secret appears in source
or evidence, the target environment is ambiguous, time is unsynchronized, or
authorization cannot be demonstrated.



## Current implementation boundary

Protocol-neutral runtime, storage, validation, replay, and observability frameworks are present. The offline MQTT experiment is unregistered and optional; it cannot connect, authenticate, subscribe, or publish. Systemd receiver units remain inert until reviewed endpoint configuration exists. The expanded safety scan covers all executable source and service definitions.
