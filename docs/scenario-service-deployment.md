# Scenario receiver service deployment

This design packages the verified Java receiver as one foreground systemd service. It is not installed, enabled, or started by the repository. The authoritative transport remains `scenario.eew.shakealert.org:61612`, OpenWire over verified TLS, account `QuakeLogic-SA1`, and exact Topic `eew.test_QuakeLogic-SA1.dm.data`.

## Process and restart boundary

systemd owns the process; there is no daemonization or PID file. `bin/java-receiver` verifies the pinned runtime and then uses `exec` to replace itself with `/usr/bin/java`. `Restart=no` is intentional. This is distinct from broker retry: the receiver has no failover URI, fallback account, or in-process retry/reconnect loop. A failed activation requires operator review and an explicit new start.

## Files and permissions

| Path | Ownership | Mode | Lifetime | Purpose |
|---|---|---:|---|---|
| `/run/shakealert-scenario-receiver` | `quakelogic:quakelogic` | `0750` | removed after stop | instance lock and atomic `health.json` |
| `/var/lib/shakealert-scenario-receiver` | `quakelogic:quakelogic` | `0750` | persistent | `captures/`, `rejections/`, and sanitized `incidents/` |

systemd creates both roots. Health and rejection files are `0640`; child directories are `0750`, reinforced by `UMask=0027`. Logs use the system journal. Credentials stay separate, are not copied, and are exposed read-only by the sandbox.

The service runs as `quakelogic` because established checks require that credential owner. A future dedicated `shakealert-scenario` user requires a separately authorized credential ownership migration and owner-policy change; weakening the check is not acceptable.

## Shutdown

systemd sends `SIGTERM`. `ScenarioReceiverProcessLifecycle` installs a narrowly scoped Java 21 TERM handler that calls only `requestShutdown()`; the main thread wakes and owns all blocking JMS teardown. A bounded JVM shutdown hook remains as fallback for non-TERM JVM shutdown and likewise performs no JMS work. The internal drain deadline is 30 seconds; the hook wait is 35 seconds; `TimeoutStopSec=45s` leaves 10 seconds for final process and systemd accounting.

The TERM bridge uses `sun.misc.Signal` from the JDK `jdk.unsupported` module. This internal API is intentionally isolated in one class and covered by a real subprocess SIGTERM test. It is necessary because the standard Java shutdown-hook API preserves signal-derived exit status 143 even after successful hook coordination; the service contract requires normal TERM teardown to return from `main()` with exit code 0. No `System.exit()`, `Runtime.halt()`, blocking JMS call, reconnect, or broker operation occurs in the TERM handler.

`SendSIGKILL=no` keeps SIGKILL out of normal timeout handling. Successful TERM reaches `STOPPED` and returns from `main()` with exit code 0. If the deadline expires, health remains `FAILED`, never falsely `STOPPED`, and the process exits nonzero. Review health and journal output, preserve captures, and explicitly authorize any escalated termination.

## Health and readiness

`health.json` uses temporary file, file fsync, atomic rename, and directory fsync. Its only fields are: `lifecycle_state`, `state_entered_utc`, `process_started_utc`, `connected`, `authenticated`, `subscribed`, `connection_started`, `account_id`, `endpoint_name`, `exact_destination`, `messages_received`, `captures_committed`, `capture_failures`, `messages_acknowledged`, `acknowledgement_failures`, `callbacks_in_progress`, `async_jms_error`, `parser_failed`, `parser_failure_count`, `last_error_category`, `last_error_utc`, and `shutdown_requested`. `bin/scenario-receiver-status` reports ready only for `RUNNING` plus connected, authenticated, subscribed, and connection-started, with no async JMS or parser failure. Liveness alone is insufficient.

Parser `FAILED` leaves the process alive so native capture remains observable. It immediately sets `parser_failed=true`, increments `parser_failure_count`, records sanitized category/time, and makes readiness false. Parsing does not restart. External monitoring should alert and await operator action.

## Rejection retention and duplicates

Expected rejection records contain only UTC timestamp, capture ID, payload SHA-256, sanitized category, and an optional already-parsed Event/update identity; they contain no payload text, credentials, broker headers, or raw exception strings. Retention is 1,000 files, 64 MiB total, and 30 days. Expired records are deleted first; remaining records are ordered by modification time then filename and deleted oldest-first until count and byte limits both hold. The directory is fsynced.

The latest asynchronous JMS failure is preserved separately at
`incidents/async-jms-latest.json` (`0750` directory, `0640` file, maximum 4,096
bytes). It uses type-only bounded classification and contains no raw exception
message or stack trace. Publication uses a temporary file, file fsync, atomic
replacement, and directory fsync. A diagnostic-write failure never blocks the
service's fail-closed ordered teardown.

Duplicate detection remains activation-local. After restart, an old redelivery may be processed again; every delivery is still captured. The smallest future persistent design is an atomic bounded store keyed by trusted JMS ID and `(event/update identity, payload SHA-256)`, committed only after accepted processing.

## Hardening exceptions

The unit enables no-new-privileges, strict filesystem protection, private temporary/device namespaces, empty capability sets, kernel/control-group/clock/hostname protection, namespace/realtime restrictions, and only Unix/IPv4/IPv6 address families. `MemoryDenyWriteExecute` is omitted because the Java JIT needs executable memory. Hostname-based broker addressing prevents stable unit-level IP allowlisting. `PrivateUsers` would break credential-owner and state ownership checks. Writable paths are limited to the systemd runtime/state roots.
