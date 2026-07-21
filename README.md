# USGS ShakeAlert Reception Laboratory

> **SHAKEALERT LAB — NO OPERATIONAL OUTPUTS**

This is the development repository for Phase 0 (governance and isolation) and
Phase 1 (Ubuntu platform baseline). It contains no message receiver, external
connection logic, protocol implementation, deployment installation, or
operational output.

## Safety invariant

Every future application entry point must call `bin/safety_preflight` before
doing work. The check fails closed unless `ALLOW_OPERATIONAL_OUTPUTS` is
present and is exactly `false`.

## Development setup and validation

```bash
./scripts/setup_development.sh
./scripts/collect_platform_inventory.sh
./scripts/run_acceptance_checks.sh
```

All scripts operate inside this repository and require no elevated privileges.
Generated inventory and validation evidence is ignored by Git and must be
reviewed for sensitive information before sharing.

## Phase boundary

The repository does not connect to USGS, inspect credentials, assume a
transport, alter CUBE/PX-01, install services, or activate physical outputs.
Phase 2 requires separate written approval.

