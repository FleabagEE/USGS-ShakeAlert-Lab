# Baseline Package Manifest

This is a requirements template, not an installation manifest.

| Package | Phase 1 justification |
|---|---|
| `ca-certificates`, `openssl` | trust store and later TLS diagnostics |
| `curl`, `netcat-openbsd`, `dnsutils`, `traceroute` | controlled readiness diagnostics |
| `chrony` | time synchronization evidence |
| `python3`, `python3-venv`, `python3-pip` | isolated future application development |
| `jq`, `unzip`, `git` | structured local inspection and source control |
| `tcpdump`, `lsof` | authorized diagnostics; never embedded in application |
| `rsyslog`, `logrotate`, `auditd` | future host logging, retention, and audit capabilities |

Do not install protocol-specific clients until approved discovery proves the
actual transport. In particular, this document makes no MQTT determination.

