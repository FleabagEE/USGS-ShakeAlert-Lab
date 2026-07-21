# Initial Development Architecture

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
          X no endpoint connection or message transport
          X no receiver, publisher, CUBE/PX-01 pathway
          X no host deployment or operational output
```

The future native protocol remains unknown. This design intentionally contains
no messaging-stack decision.

