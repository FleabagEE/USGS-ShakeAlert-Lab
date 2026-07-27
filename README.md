# USGS ShakeAlert Reception Laboratory

> **SHAKEALERT LAB — NO OPERATIONAL OUTPUTS**

This repository builds a passive Ubuntu laboratory for authorized ShakeAlert
interface discovery. It must never activate physical outputs, publish to CUBE,
or modify CUBE/PX-01 software.

## Current status

| Specification phase | Status | Decision |
|---|---|---|
| Phase 0 — governance and safety controls | Technical host controls implemented; formal named review pending | Conditional go for baseline work only |
| Phase 1 — Ubuntu platform baseline | Host provisioned and time synchronized; endpoint-specific network evidence pending | No-go for endpoint discovery |
| Phases 2–17 | Not accepted | Blocked by approved inputs, credentials, connectivity, captures, or earlier phase gates |

The Ubuntu 24.04 development host has an isolated, non-login `shakealert`
account and a protected `/opt/quakelogic/shakealert-lab` tree. Baseline packages,
Chrony, auditd, rsyslog, permissions, and the fail-closed interlock are installed.
No USGS endpoint has been contacted and no USGS credential has been installed.

Existing `src/` code after Phase 1 is laboratory architecture scaffolding. The
MQTT adapter is not evidence that USGS uses MQTT and must not be configured for
an external endpoint until protocol discovery and approval are complete.

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

Before Phase 2, provide protected production and scenario access materials and
approve endpoint-specific DNS/TCP/TLS checks. Required hostnames, ports,
transport versions, destinations, VPN/proxy rules, allow-list requirements,
and TLS SNI requirements must be verified without guessing. Formal Phase 0/1
review also remains to be signed by named owners.
