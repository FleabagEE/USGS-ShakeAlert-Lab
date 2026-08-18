# USGS ShakeAlert Reception Laboratory

> **SHAKEALERT LAB — NO OPERATIONAL OUTPUTS**

This repository builds a passive Ubuntu laboratory for authorized ShakeAlert
interface discovery. It must never activate physical outputs, publish to CUBE,
or modify CUBE/PX-01 software.

## Current status

The Scenario Server proof-of-concept is successful. The authoritative endpoint
is `scenario.eew.shakealert.org:61612`, using ActiveMQ OpenWire over
hostname-verified TLS with the explicit `QuakeLogic-SA1` account. The exact,
non-wildcard Event Topic is `eew.test_QuakeLogic-SA1.dm.data`.

During the authorized M4.6 Westmoreland Event-only Scenario on 2026-08-18, the
already connected non-durable Topic consumer received eight Event updates.
Eight bounded native captures committed successfully, all declared payload
sizes and SHA-256 values were verified, and no JMS, transport, capture,
publishing, fallback, or Production error occurred. Sanitized evidence is
preserved locally under the Git-ignored `evidence/` boundary.

Production connectivity remains untested and unauthorized. The offline MQTT
adapter is not evidence that either USGS endpoint uses MQTT.

## Safety invariant

Every application entry point must call `bin/safety_preflight` before doing
work. Startup fails closed unless `ALLOW_OPERATIONAL_OUTPUTS` is present and is
exactly `false`. An `UNKNOWN` environment classification can never enter an
operational pathway; this project contains no operational pathway.

The Java Scenario receiver has no publishing, wildcard, retry, fallback,
durable-subscription, client-ID, Queue, or Production path. Account selection
derives only the protected account-scoped credential directory.

## Reproducible setup and validation

Repository-only Python validation:

```bash
./scripts/setup_development.sh --create-venv
PYTHONPATH=src .venv/bin/pytest -q
```

The Java receiver is defined by a pinned Java 21/Maven 3.8.7 build. Its isolated
`.mvn/repository` has been provisioned and sealed by
`build-support/maven-artifacts.sha256`. Offline compilation, all 10 JUnit
behavioral tests, Enforcer and duplicate-class checks, runtime-classpath
verification, and two-build JAR reproducibility have passed. See
`docs/java-build-reproducibility.md`.

Authorized Ubuntu host provisioning and validation:

```bash
sudo ./scripts/setup_ubuntu.sh
sudo ./tests/security/verify_host_baseline.sh
./scripts/collect_platform_inventory.sh
```

## Current gate

The Scenario end-to-end proof-of-concept and repository build verification are
complete. The pinned offline Maven/JUnit suite, dependency convergence,
duplicate-class analysis, runtime guards, checksum manifest, and reproducible
packaging have passed. No further Scenario connection is required for this
milestone.

Production endpoint discovery, authorization, listening, CUBE/PX-01 mapping,
and operational-output decisions remain separate future gates.
