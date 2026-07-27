# Baseline Package Manifest

Installed and verified on Ubuntu 24.04 LTS on 2026-07-27. Versions remain
subject to Ubuntu security updates; the inventory script records the deployed
state. No protocol-specific messaging stack was installed.

| Package | Installed version | Phase 1 justification |
|---|---|---|
| `ca-certificates` | `20260601~24.04.1` | System trust store |
| `openssl` | `3.0.13-0ubuntu3.11` | Later authorized TLS diagnostics |
| `curl` | `8.5.0-2ubuntu10.11` | Controlled HTTP readiness diagnostics |
| `netcat-openbsd` | `1.226-1ubuntu2` | Controlled TCP readiness diagnostics |
| `dnsutils` | `1:9.18.39-0ubuntu0.24.04.5` | Controlled DNS diagnostics |
| `traceroute` | `1:2.1.5-1` | Authorized route diagnostics |
| `chrony` | `4.5-1ubuntu4.2` | Time synchronization and evidence |
| `python3` | `3.12.3-0ubuntu2.1` | Application runtime |
| `python3-venv` | `3.12.3-0ubuntu2.1` | Isolated Python environments |
| `python3-pip` | `24.0+dfsg-1ubuntu1.3` | Pinned Python dependency installation |
| `jq` | `1.7.1-3ubuntu0.24.04.2` | Structured local inspection |
| `unzip` | `6.0-28ubuntu4.1` | Supplied archive inspection |
| `git` | `1:2.43.0-1ubuntu7.3` | Source control |
| `tcpdump` | `4.99.4-3ubuntu4.24.04.1` | Explicitly authorized diagnostics only |
| `lsof` | `4.95.0-1build3` | Local process/socket inspection |
| `rsyslog` | `8.2312.0-3ubuntu9.3` | Host logging |
| `logrotate` | `3.21.0-2build1` | Log retention controls |
| `auditd` | `1:3.1.2-2.1build1.1` | Security audit trail |

Do not install protocol-specific clients until approved discovery proves the
actual transport. In particular, this document makes no MQTT determination.
