# Permissions Report

## Deployed laboratory

Provisioned on 2026-07-27:

| Location | Owner | Mode | Purpose |
|---|---|---:|---|
| `/opt/quakelogic` | `root:shakealert` | `0750` | Isolated parent |
| `/opt/quakelogic/shakealert-lab` | `root:shakealert` | `0750` | Laboratory root |
| `app/`, `bin/`, `config/`, `docs/`, `schemas/`, `scripts/`, `services/`, `tests/`, `tools/` | `root:shakealert` | `0750` directories | Service-readable, not service-writable |
| `credentials/` | `shakealert:shakealert` | `0700` | Protected credential boundary |
| credential files, when authorized | `shakealert:shakealert` | `0600` required | Secret inputs |
| `config/lab.env` | `root:shakealert` | `0640` | Passive safety configuration |
| `evidence/`, `logs/`, `messages/*/` | `shakealert:shakealert` | `0750` | Service-writable laboratory data |
| `bin/safety_preflight` | `root:shakealert` | `0750` | Root-controlled interlock |

The `shakealert` account is a system account with home
`/opt/quakelogic/shakealert-lab`, shell `/usr/sbin/nologin`, and no `sudo` or
`admin` group. Audit rules monitor changes to credentials, configuration, and
service definitions.

## Development checkout

The checkout remains owned by the developer. `credentials/` is `0700`; logs
and message directories are `0750`; secret files, captures, logs, evidence, and
confidential references are ignored by Git.
