# Lesson 14: Observability without authority

The main modules are:

- `src/shakealert_lab/observability/dashboard.py`
- `src/shakealert_lab/observability/status.py`
- `src/shakealert_lab/logging_setup.py`
- `src/shakealert_lab/security/redaction.py`
- `src/shakealert_lab/metrics.py`

The governing rule is:

> Observability may describe system state, but it must never own or change that state.

```
Runtime components
      │
      ▼
Immutable snapshots
      │
      ├──► metrics
      ├──► structured logs
      └──► local dashboard

No reverse control path
```

## Why observability exists

A system can fail correctly and still be impossible to operate if it cannot explain:

- what state it is in
- why it entered that state
- which activation generation failed
- whether evidence was preserved
- whether the queue saturated
- which validation rule rejected a message
- whether TLS, authentication, or subscription failed
- whether displayed information is current
- whether data came from Scenario, Production, or replay

Observability converts internal state into bounded engineering evidence.

It is not an afterthought. It is part of the failure-containment architecture.

## What happens if it is removed

Without observability, operators infer state from secondary symptoms:

```
No dashboard update
      │
      ├─ no earthquake?
      ├─ broker disconnected?
      ├─ worker deadlocked?
      ├─ storage full?
      ├─ parser failed?
      └─ dashboard stale?
```

These causes require different responses.

Poor observability increases recovery time and encourages dangerous interventions such as:

- repeated restarts
- unnecessary credential changes
- disabling safety checks
- reconnecting repeatedly
- inspecting secret-bearing files
- treating absence of alarms as proof of health

A system that cannot explain its failure will eventually tempt someone to bypass it.

## Observability has three outputs

The repository separates three related mechanisms.

### Metrics

Metrics summarize quantities and current values:

```
messages_received = 100
queue_depth = 4
reconnects = 2
connected = false
```

They are useful for trends, thresholds, and dashboards.

### Logs

Logs record discrete events:

```
receiver entered FAILED
queue saturation detected
authentication rejected
capture commit failed
```

They explain transitions and causal evidence.

### Status snapshots

Status gives a coherent operator-facing view:

```
Scenario:
    connected = false
    heartbeat = unknown
    TLS = verified
```

Each serves a different purpose. Logs should not be used as a metrics database, and metrics should not be treated as a forensic journal.

## The observer pattern

The architectural relationship should be one-way:

```
System of record
      │
      ▼
Snapshot producer
      │
      ▼
Observer
```

The dashboard should not hold references that allow it to mutate receiver state.

The safest dashboard consumes immutable snapshots produced by the lifecycle owner.

This prevents accidental control coupling:

```
HTTP request thread
      ──X──► receiver lifecycle mutation
```

A dashboard request should never call `start()`, `stop()`, reconnect, clear a failure latch, or alter configuration.

## Why snapshots matter

The repository's `Metrics.snapshot()` returns a copy.

Without a snapshot, the dashboard might observe state midway through a transition:

```
connected = true
subscription = none
state = RUNNING
```

Those fields may have been read from three different instants.

A stronger system binds related status to:

- one activation generation
- one snapshot sequence
- one observation time
- one lifecycle state

For example:

```
StatusSnapshot
├── snapshot_sequence = 1442
├── generated_monotonic
├── generated_utc
├── activation_generation = 8
├── lifecycle = FAILED
├── transport
├── storage
└── processing
```

A copied dictionary prevents mutation, but it does not automatically guarantee cross-field consistency. One owner should construct the complete snapshot.

## Status truth must be time-bounded

Every status observation has an age.

A dashboard showing:

```
connected = true
```

is unsafe if the value was last updated ten minutes ago.

A professional status field carries:

```
ObservedValue
├── value
├── observed_at
├── source
├── activation_generation
├── validity_period
└── quality
```

When its validity period expires, the status becomes `UNKNOWN` or `STALE`. It must not remain green indefinitely.

This follows the lease pattern from Lesson 13.

## Explicit unknown values

The repository's status model permits values such as:

```
connected = None
heartbeat_healthy = None
tls_status = None
```

That demonstrates an important rule:

> unknown ≠ false
> unknown ≠ healthy

Examples:

- `heartbeat_healthy = false`: a verified heartbeat contract exists and its deadline was missed.
- `heartbeat_healthy = unknown`: heartbeat semantics are unavailable or no authoritative observation exists.
- `heartbeat_healthy = true`: verified heartbeat evidence is fresh.

Collapsing unknown into false creates false alarms. Collapsing it into true conceals missing evidence.

## Environment separation

`LaboratoryStatus` keeps Scenario and Production status separate.

This avoids a dangerous aggregate:

```
connected = true
```

Which environment is connected?

A safer representation is:

```
scenario.connected   = true
production.connected = false
```

Even better, each connection status should carry an exact `ConnectionIdentity` established by configuration and `SafetyAuthority`.

Environment identity must never be inferred from traffic volume or a friendly display name.

## The laboratory banner

The dashboard displays:

> LABORATORY ONLY — NOT AN OPERATIONAL WARNING SYSTEM

This is a useful human-factors control.

It reduces the chance that:

- a screenshot is mistaken for an operational product
- a test operator assumes public-warning authority
- replayed messages are interpreted as current alerts
- laboratory status is shown in an operational environment

But a banner is not a technical safety boundary.

If the dashboard can reach an actuator, the banner provides no containment. Human labeling supplements architectural isolation—it does not replace it.

## Loopback-only binding

The dashboard refuses addresses other than:

- `127.0.0.1`
- `::1`
- `localhost`

This is a network containment decision.

It prevents accidental exposure through:

```
0.0.0.0
```

which would listen on external interfaces.

The pattern is allow-list binding:

```
requested address
      │
      ├─ exact approved loopback → allowed
      └─ anything else           → rejected
```

This is stronger than binding first and warning afterward.

## What loopback does not guarantee

Loopback reduces network exposure, but it does not provide complete security.

Other local processes or users may still access the service. Risks include:

- sensitive status disclosure
- local cross-user access
- browser-origin attacks
- malicious local software
- denial of service
- stale or misleading information
- HTML injection through unescaped status fields

A higher-assurance dashboard may also require:

- Unix-domain socket access
- filesystem permissions
- local authentication
- process isolation
- response size limits
- request concurrency limits
- strict output encoding
- no state-changing endpoints

## Health endpoint semantics

The current `/health` endpoint always returns HTTP 200 if the handler successfully produces JSON.

That proves:

> The dashboard thread answered the request.

It does not necessarily prove:

> The receiver is ready.

Mature systems often separate:

- **`/live`** — Is this process responsive?
- **`/ready`** — Can it safely perform its assigned service?
- **`/status`** — What detailed evidence explains its state?

Returning 200 from `/health` for a failed receiver may be correct if `/health` means liveness. It is incorrect if an orchestrator interprets it as readiness.

Endpoint semantics must be explicit.

## Structured logging

The repository emits JSON fields including:

- UTC timestamp
- level
- logger name
- message
- structured extra fields

Structured logging is valuable because machines can reliably filter:

```
event_code = QUEUE_SATURATED
activation_generation = 8
state = FAILED
```

This is better than parsing prose such as:

```
Something went wrong while processing queue 8
```

A professional event should carry stable identifiers:

```
LogEvent
├── event_code
├── severity
├── timestamp_utc
├── monotonic_offset
├── activation_generation
├── component
├── safe diagnostic fields
└── correlation identifiers
```

Human-readable text can change. Machine contracts should not depend on wording.

## Logs are evidence, not control flow

Code should not determine safety behavior by searching its own log messages.

**Unsafe design:**

```
if log contains "authentication failed":
    stop receiver
```

**Safe design:**

```
authentication result
      ├──► supervisor transition
      └──► diagnostic event
```

The lifecycle transition and log are both consequences of the same typed fact.

This avoids dependence on text formatting, logging availability, or localization.

## Redaction boundary

The repository redacts structured fields whose keys contain markers such as:

- password
- secret
- token
- private key
- credential

It also replaces byte values with their lengths.

This demonstrates conservative structured redaction:

```
password = value
      ↓
password = <redacted>
```

Redaction should occur before serialization and before data reaches an external log sink.

## Redaction is defense in depth

The stronger rule is:

> Secrets should never reach the logging API in the first place.

Redaction exists because mistakes happen, but it cannot make arbitrary logging safe.

A secret might leak through:

- an exception message
- a URL
- an object's string representation
- an authorization header
- an unrecognized key name
- a nested list
- the main prose message
- a stack trace
- a third-party library
- encoded or transformed content

Therefore:

```
Secret ownership boundary
      │ prevents secret propagation
      ▼
Structured diagnostic model
      │ permits only approved fields
      ▼
Redactor
      │ defense in depth
      ▼
Log sink
```

## An important repository limitation

The current `JsonFormatter` redacts the structured fields dictionary, but it uses the main log message directly.

If a caller creates a message containing a secret, key-based structured redaction cannot remove it.

Likewise, the current redactor:

- relies mainly on field names
- handles nested mappings but not arbitrary lists
- preserves unknown objects
- cannot prove arbitrary exception messages are safe
- does not enforce a strict allow-list of diagnostic fields

Your Lesson 4 rule was stronger:

> If the redactor cannot classify a value safely, suppress it.

For higher assurance, logging should use typed, allow-listed diagnostic events rather than unrestricted strings.

## Allow-list diagnostics

A fail-closed diagnostic event might permit only:

```
AuthenticationDiagnostic
├── classification
├── JMS exception class
├── linked exception class
├── sanitized reason code
├── broker error code
└── activation generation
```

Everything else is omitted.

This is safer than taking an arbitrary exception graph and trying to remove every possible secret after the fact.

The principle is:

> allow known-safe fields

rather than:

> accept everything and search for known-dangerous fields

## Payload handling

The redactor displays byte values as:

```
<bytes:N>
```

This is useful because it preserves diagnostic size without printing payload contents.

Payloads may contain:

- personal information
- credentials accidentally sent upstream
- operational details
- proprietary schema content
- malicious terminal sequences
- very large data

A log should record a capture ID and safe summary, not duplicate native content.

```
capture_id = C42
payload_size = 843
payload_hash = ...
```

The authoritative bytes remain in the protected capture store.

## Log injection and output encoding

Even structured JSON is not automatically safe.

Untrusted strings can contain:

- newline characters
- terminal escape sequences
- HTML
- control characters
- misleading field-like text

JSON encoding handles structural escaping, but whichever system displays the log must still encode it for its output context.

The dashboard's HTML representation embeds serialized status inside a page. A production implementation should apply HTML escaping, even if status is expected to be internally generated.

Trust boundaries should assume future fields may contain untrusted data.

## Observability cardinality

Metrics and labels must be bounded.

Dangerous labels include:

- event ID
- message ID
- capture ID
- raw destination
- exception message
- username
- arbitrary schema field

If each unique value creates a new time series, memory use can grow without limit.

Good metric dimensions are small, controlled enumerations:

```
environment = scenario | production
state = running | failed | stopped
failure_class = auth | tls | storage | queue
```

High-cardinality identifiers belong in bounded logs or indexed evidence stores.

## Observability failure policy

What happens if the dashboard fails?

For a passive receiver, failure of a convenience dashboard may allow reception to continue if:

- safety supervision is independent
- durable capture remains healthy
- required alarms still reach operators
- dashboard failure cannot hide a mandatory readiness condition

But failure of mandatory audit logging may be different.

The architecture should classify observability components:

| Component | Failure consequence |
|---|---|
| Optional local dashboard | Degraded |
| Metrics exporter | Degraded |
| Required safety alarm path | System failure |
| Required audit trail | Possibly fail closed |
| Native capture store | System failure |
| Debug logging | Usually degraded |

"Logging failed" should not always crash the system, but required evidence loss must never be silently ignored.

## Observability backpressure

Log sinks and dashboard clients can be slow.

The receiver callback must never block indefinitely because:

- disk logging is slow
- a dashboard client stopped reading
- metrics export is unavailable
- DNS for a monitoring service failed

Possible strategies include:

- bounded diagnostic queues
- severity-based reservation
- aggregation of repetitive events
- rate limiting
- local fallback records
- latched observability failure
- supervisor escalation when required evidence cannot be retained

Dropping debug logs may be acceptable. Dropping the only record of a safety failure may not be.

## Security telemetry versus secrets

Security diagnostics need enough detail to distinguish:

- unknown account
- invalid password
- disabled account
- unauthorized destination
- TLS failure
- protocol mismatch

But they must not include:

- username
- password
- token
- authorization header
- credential path contents
- private keys
- complete untrusted exception graphs

The right solution is structured classification produced at the boundary where safe evidence is known.

This is why broker evidence should become:

```
AUTHENTICATION_CLASSIFICATION=ACCOUNT_DISABLED
```

rather than printing an entire raw server response.

## Cross-industry equivalents

| Domain | Equivalent |
|---|---|
| Automotive | Diagnostic trouble codes, freeze frames, DEM events, watchdog checkpoint status |
| Aerospace | Built-in-test telemetry, fault-isolation records, maintenance messages, flight-data monitoring |
| Industrial PLCs | Alarm journals, quality flags, historian tags, diagnostic function blocks |
| Robotics | Node diagnostics, health topics, rosbag references, lifecycle-state dashboards |
| Linux kernel | Tracepoints, counters, sysfs, debugfs, netlink status, rate-limited kernel logs |
| RTOS firmware | Event logs, fault snapshots, trace buffers, watchdog reports, diagnostic UART with redaction |

The shared principle is:

> observe enough to diagnose
> without creating a new control path
> or leaking protected state

## Design patterns demonstrated

- **Observer Pattern**: runtime emits snapshots without giving observers ownership.
- **Snapshot Pattern**: status is copied into stable representations.
- **Health Aggregator**: multiple facts form a readiness conclusion.
- **Structured Event Pattern**: stable codes accompany safe fields.
- **Security Gateway**: redaction sits before external sinks.
- **Allow-List Boundary**: only approved local bind addresses are accepted.
- **CQRS Principle**: status queries are separated from lifecycle commands.
- **Bulkhead Pattern**: dashboard or logging failure should not automatically stall reception.
- **Data Minimization**: logs carry references and summaries, not native payloads.

## Current repository strengths

The current implementation correctly demonstrates:

- loopback-only dashboard binding
- refusal to bind to `0.0.0.0`
- a prominent laboratory banner
- no state-changing HTTP endpoints
- separate Scenario and Production status
- explicit unknown values
- no-store HTTP responses
- copied metric snapshots
- structured JSON logs
- basic secret-key redaction
- suppression of default HTTP access logs
- payload-byte summarization rather than payload printing

## Current repository limitations

A production-grade evolution would need:

- separate liveness and readiness endpoints
- snapshot timestamps and activation generations
- status expiration
- consistent multi-component snapshots
- HTML output escaping
- bounded HTTP concurrency and response size
- local access control
- typed allow-listed diagnostic events
- recursive handling of sequences
- suppression of unsafe exception messages
- protection against secrets in the primary log message
- log rotation and retention
- disk-full behavior
- audit-log integrity
- rate limiting
- metric-name and cardinality controls
- monotonic event timing
- explicit observability degradation policy

## Architecture review questions

1. The dashboard thread is responsive, but the receiver is in latched `FAILED` state. What should `/live`, `/ready`, and `/status` each return?

2. A third-party library produces an exception whose message may contain a broker URL with credentials. Where should sanitization occur, and what information should the logger receive?

3. The required audit-log queue becomes full while ordinary reception remains healthy. Should the receiver continue, degrade, or disconnect? Define which evidence makes the decision.

4. How would you produce one consistent status snapshot across transport, queue, storage, worker, and heartbeat state without letting the dashboard acquire every component's locks?

5. Which metrics would you permit as bounded labels, and which identifiers must remain in logs or the capture store to prevent unbounded cardinality?
