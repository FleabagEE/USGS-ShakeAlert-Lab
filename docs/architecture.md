# Laboratory Framework Architecture

```text
┌──────────────── DEVELOPMENT REPOSITORY BOUNDARY ────────────────┐
│ README + governance + templates                                │
│               │                                                │
│ non-privileged setup ──> repository directories                │
│               │                                                │
│ safety preflight ─────> fail closed unless outputs=false       │
│               │                                                │
│ local inventory + acceptance checks ──> ignored evidence files │
└────────────────────────────────────────────────────────────────┘
          X no endpoint connection or selected USGS transport
          X no active receiver, publisher, or CUBE/PX-01 pathway
          host baseline + inert units; X no operational output
```

The future native protocol remains unknown. This design intentionally contains
no messaging-stack decision.



## Implemented credential-independent architecture

The repository now includes fail-closed configuration and credential references, an explicit transport registry with no default, atomic lossless native capture, generic validation and sequence classification, provenance-only normalization, internal replay, a loopback dashboard, reliability and heartbeat utilities, structured redaction, and inert hardened systemd units. No adapter is selected for USGS until verified evidence is supplied.
