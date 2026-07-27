# Operations Runbook

1. Confirm banner, authorization, UTC/Chrony, disk, configuration review, endpoint matrix, and protected credential status.
2. Run repository and host acceptance tests.
3. Validate configuration with `ALLOW_OPERATIONAL_OUTPUTS=false python -m shakealert_lab validate-config --config FILE`.
4. Start only the approved environment unit; never enable both without simultaneous-stream approval.
5. Monitor structured logs, `/health`, heartbeat, reconnects, queue, captures, clock, and disk.
6. On ambiguity, TLS/auth error, clock critical state, storage failure, or UNKNOWN classification, stop reception and preserve evidence.
7. Never modify CUBE/PX-01 or enable an operational route.

Actual start/stop and acknowledgment details remain adapter-specific.
