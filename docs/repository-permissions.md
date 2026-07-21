# Repository Permission Guidance

Development setup applies only repository-local permissions:

| Location | Recommended mode | Purpose |
|---|---:|---|
| `credentials/` | `0700` | ignored placeholder location for later protected inputs |
| `logs/` | `0750` | ignored local logs |
| `messages/*/` | `0750` | ignored local captures and replay artifacts |
| `config/*.template` | `0640` or stricter | non-secret templates |
| scripts | `0750` or `0755` | reviewed local execution |

The acceptance check detects credential-directory files without reading their
contents. Production ownership, service identities, and deployment permissions
are intentionally unspecified until a separately approved deployment design.

