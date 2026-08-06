# USGS ShakeAlert Reception Laboratory

> **SHAKEALERT LAB — NO OPERATIONAL OUTPUTS**

This repository builds a passive Ubuntu laboratory for authorized ShakeAlert
interface discovery. It must never activate physical outputs, publish to CUBE,
or modify CUBE/PX-01 software.

## Current Status

The credential-independent passive receiver, native-capture, validation,
normalization, replay, observability, and fail-closed safety frameworks are
implemented. Authorized checks against the Scenario Server verified DNS, TCP,
the public TLS chain and hostname, TLS 1.3, and ActiveMQ OpenWire negotiation.

Broker authentication is currently blocked by invalid or unconfirmed broker
credentials. The broker returned a sanitized `JMSSecurityException` indicating
that the username or password was invalid. No subscription was created, no live
Scenario message was received or captured, and no publishing or operational
output occurred. Live Scenario Server integration is not complete. The project
is awaiting USGS confirmation of the correct broker credentials and Scenario
account authorization before another connection attempt.

Production connectivity remains untested and unauthorized. The offline MQTT
adapter is not evidence that either USGS endpoint uses MQTT.

## Safety invariant

Every application entry point must call `bin/safety_preflight` before doing
work. Startup fails closed unless `ALLOW_OPERATIONAL_OUTPUTS` is present and is
exactly `false`. An `UNKNOWN` environment classification can never enter an
operational pathway; this project contains no operational pathway.

## Reproducible setup and validation

Repository-only development setup:

```bash
./scripts/setup_development.sh --create-venv
./scripts/run_acceptance_checks.sh
```

Authorized Ubuntu host provisioning and validation:

```bash
sudo ./scripts/setup_ubuntu.sh
sudo ./tests/security/verify_host_baseline.sh
./scripts/collect_platform_inventory.sh
```

`setup_ubuntu.sh` is idempotent and refreshes only official Ubuntu package
sources, so unrelated third-party repositories cannot alter or block the
baseline. Generated evidence is ignored by Git and must be reviewed before it
is shared.

## Current gate

Before live Scenario integration can proceed, USGS must confirm the broker
credential pair and that the account is authorized for the assigned exact Event
topic. Subscription and message-capture validation remain blocked. Production
access, protocol, destinations, and authorization remain separate open gates.

## Credential-independent framework progress

All endpoint-independent frameworks and required documentation templates are
implemented. The transport registry deliberately contains no default USGS
adapter. Receiver units fail closed unless an active reviewed configuration
exists, explicit connection authorization is true, and a verified adapter is
registered. Scenario subscription, live capture, field discovery, production
traffic, and final CUBE selection remain blocked by authorized USGS access and
evidence.
