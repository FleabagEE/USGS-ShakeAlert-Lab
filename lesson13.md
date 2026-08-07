# Lesson 13: Liveness supervision and health semantics

The main modules are:

- `src/shakealert_lab/health.py`
- `src/shakealert_lab/reliability.py`
- `src/shakealert_lab/metrics.py`
- `src/shakealert_lab/observability/status.py`

The governing rule is:

> "The process is running" does not prove that the system is functioning.

A receiver can be:

- alive as a process
- connected at TCP
- connected at TLS
- authenticated
- subscribed
- receiving broker traffic
- receiving valid heartbeats
- preserving messages
- processing within its deadline
- ready to provide service

Those are separate facts.

## The health hierarchy

A professional health model resembles:

```
Process alive
    │
    ▼
Runtime supervisor healthy
    │
    ▼
Transport connected
    │
    ▼
TLS/authentication valid
    │
    ▼
Subscription established
    │
    ▼
Broker traffic observed
    │
    ▼
Heartbeat fresh
    │
    ▼
Capture storage healthy
    │
    ▼
Processing current
    │
    ▼
Service ready
```

Higher layers depend on lower ones, but one Boolean cannot safely represent the entire chain.

## Why these modules exist

Distributed failures are often partial.

Examples:

- TCP remains established while no messages arrive.
- Broker heartbeats arrive while capture storage is full.
- Messages are stored while worker processing is stalled.
- The process responds to `/health`, but its receiver task is dead.
- The receiver reconnects repeatedly but never authenticates.
- Event traffic is quiet because no earthquake occurred—not because the connection failed.
- The system clock is wrong, causing healthy heartbeats to look stale.
- A dashboard shows stale metrics from before a worker failure.

Liveness supervision gives the runtime enough evidence to distinguish these conditions.

## What happens if health supervision is removed

The system becomes "green by process existence."

An operator sees:

```
service process: running
```

while the actual path may be:

```
broker ──X── receiver
receiver ──X── storage
storage ──X── processing
```

This creates silent loss—the exact failure mode your architecture repeatedly rejects.

Without explicit supervision:

- deadlocks may remain invisible
- stale connections appear healthy
- queue growth is discovered only after exhaustion
- reconnect storms overload the broker
- operators cannot distinguish idle traffic from failed reception
- orchestration may route work to an incapable instance
- watchdog resets may occur without useful diagnostic evidence

## Liveness, readiness, and correctness

These terms must remain separate.

### Liveness

> Is the software still making progress?

Examples:

- supervisor task runs
- worker heartbeat advances
- event loop cycles
- watchdog is serviced by the correct owner

### Readiness

> Can the system safely perform its assigned service now?

For this receiver, readiness could require:

- authorized configuration
- verified TLS
- authentication success
- exact subscription established
- capture storage writable
- queue below critical watermark
- clock quality acceptable
- no latched safety failure

### Correctness

> Is the system producing semantically correct results?

A process can be live and ready according to mechanical checks while still running a defective parser. Health monitoring cannot prove complete correctness.

The safest status model avoids making that claim.

## Heartbeat monitoring

The repository's `HeartbeatMonitor` stores the last observed heartbeat time and compares its age against a timeout.

Conceptually:

```
heartbeat observed
      │
      ▼
last_seen = timestamp
      │
periodic supervisor check
      │
      ├─ age ≤ timeout → healthy
      └─ age > timeout → unhealthy
```

Before any heartbeat has been observed, the monitor reports unhealthy.

That is fail-closed, but production systems may need a more descriptive state:

| State | Meaning |
|---|---|
| `UNKNOWN` | no evidence yet |
| `STARTUP_GRACE` | waiting within allowed initialization period |
| `HEALTHY` | heartbeat arrived within deadline |
| `LATE` | heartbeat deadline exceeded |
| `FAILED` | escalation policy latched failure |
| `UNSUPPORTED` | protocol has no verified heartbeat contract |

The repository already uses `None` in status models for unknown health information. This avoids falsely reporting false when heartbeat semantics have not been established.

## Heartbeat must be defined by protocol evidence

The repository explicitly states that health parsing is unavailable until a verified schema is registered.

This is a strong design decision.

The system must not assume that:

- any message counts as a heartbeat
- silence means failure
- event traffic has a fixed minimum rate
- broker keepalive equals application health
- a TCP packet proves the upstream publisher is functioning

These are different signals:

| Signal | What it proves |
|---|---|
| TCP connection | Socket relationship still appears established |
| TLS session | Encrypted authenticated channel was negotiated |
| OpenWire keepalive | Protocol peers exchange transport-level liveness |
| Broker advisory | Broker reports some management state |
| Application heartbeat | Publisher/application asserts liveness |
| Event message | Domain traffic was delivered |
| Capture counter increase | Local preservation path progressed |
| Worker progress marker | Local processing advanced |

No single signal proves the entire pipeline.

## Transport heartbeat versus application heartbeat

Suppose the broker is functioning but the upstream Scenario publisher is not.

```
Receiver ◄──── keepalive ──── Broker
Broker   ◄──── no data ────── Publisher
```

The transport can look healthy while application data has stopped.

Conversely, an application heartbeat might be queued while the network is already degraded. Its timestamp and receipt time must be distinguished.

A professional health report might say:

```
transport_connected = true
transport_keepalive_fresh = true
application_heartbeat_fresh = false
capture_pipeline_healthy = true
```

That is more useful than:

```
healthy = false
```

## Use the correct clock

The current repository uses UTC timestamps for heartbeat age. That is understandable for a small laboratory framework, but a robust runtime deadline should use a monotonic clock.

UTC can jump because of:

- NTP correction
- manual clock adjustment
- virtual-machine migration
- leap handling
- RTC correction after startup

A backward UTC jump can make a stale heartbeat appear fresh. A forward jump can cause a false timeout.

A stronger monitor records both:

```
HeartbeatObservation
├── observed_utc
└── observed_monotonic
```

Use:

- monotonic time for timeout decisions
- UTC for logs and cross-system correlation

This follows your earlier principle: no single clock owns all time semantics.

## Heartbeat timeout policy

If heartbeats are expected every H seconds, the timeout should not automatically equal H.

The timeout must allow for:

- expected interval
- scheduler jitter
- network jitter
- broker delay
- pause or maintenance behavior
- clock uncertainty
- consecutive-miss policy

One model is:

$$T_{\text{timeout}} = kH + J_{\text{network}} + J_{\text{scheduler}} + U_{\text{clock}}$$

where $k$ is the number of tolerated missed intervals.

The exact formula is less important than recording who authorized each term.

A timeout that is too short causes false failovers. One that is too long extends undetected outage time.

## Heartbeat ownership

The transport adapter may detect a protocol-native heartbeat, but it should not own service-health policy.

```
Transport adapter
    │ reports verified heartbeat observation
    ▼
Heartbeat monitor
    │ calculates freshness
    ▼
Supervisor
    │ applies lifecycle policy
    ▼
continue / degraded / reconnect / failed
```

This preserves the ownership rule used throughout the architecture:

- adapter reports facts
- monitor derives bounded state
- supervisor decides lifecycle
- transport executes connection commands

## Watchdog design

A watchdog is not merely a timer that the main loop resets.

The critical question is:

> Which evidence permits the watchdog to be serviced?

**Unsafe pattern:**

```
unrelated timer task → always kicks watchdog
```

The system may deadlock while the timer task continues running.

**Stronger pattern:**

```
Transport progress ─┐
Capture progress   ─┼─► Safety Supervisor ─► watchdog service
Worker progress    ─┤
Storage health     ─┘
```

Only the supervisor services the hardware watchdog, and only when all required progress contracts are satisfied.

For a multicore or multi-task design, each critical task may publish a generation counter. The supervisor verifies that every required counter advanced within its deadline.

## Reconnect backoff

`src/shakealert_lab/reliability.py` defines bounded exponential backoff with jitter.

Conceptually:

$$D_n = \min(D_{\max}, D_0 M^n) + J$$

where:

- $D_0$ is the initial delay
- $M$ is the multiplier
- $D_{\max}$ is the maximum
- $J$ is bounded random jitter

Backoff solves two problems:

1. It prevents one failed client from reconnecting continuously.
2. Jitter prevents many clients from reconnecting simultaneously.

Without jitter:

```
broker restarts
    ↓
1,000 clients fail together
    ↓
all wait exactly 1 second
    ↓
all reconnect together
    ↓
broker overloaded again
```

This is the thundering herd problem.

## Backoff does not grant retry authority

The existence of `BackoffPolicy` must not mean every failure is automatically retryable.

That distinction matters directly to this repository.

Possible classifications:

| Failure | Typical automatic retry policy |
|---|---|
| Temporary network interruption | Possibly retry |
| Broker unavailable | Possibly retry with bounds |
| TLS certificate invalid | Do not retry blindly |
| Hostname mismatch | Do not retry |
| Invalid credentials | Do not retry |
| Account disabled | Do not retry |
| Unauthorized destination | Do not retry |
| Local storage failure | Do not reconnect until safe |
| Safety configuration invalid | Do not connect |
| Explicit one-attempt authorization | Never retry |

The supervisor needs a `RetryAuthority`, not merely a delay calculator.

```
Failure evidence
      │
      ▼
Retry policy decision
      │
      ├─ retry forbidden → STOPPED/FAILED
      └─ retry allowed
              │
              ▼
         BackoffPolicy
```

Mechanism answers "how long?" Authority answers "whether?"

## Retry budgets

Even retryable failures need a budget.

A professional retry policy may limit:

- attempts per activation
- attempts per hour
- total outage duration
- cumulative connection load
- credential authentication attempts
- recovery attempts before operator intervention

When the budget is exhausted, the system should enter a stable terminal state and report the exact reason.

Infinite retry can transform a contained failure into a permanent resource consumer.

## When should backoff reset?

Resetting after any successful TCP connection is too early.

Consider:

```
TCP succeeds
authentication fails
backoff reset
retry immediately
```

The client can hammer the authentication service.

A stronger reset condition might require:

- verified TLS
- successful authentication
- subscription established
- a minimum stable interval
- perhaps one verified heartbeat

The reset milestone must correspond to meaningful service recovery.

## Metrics

`src/shakealert_lab/metrics.py` supplies thread-safe counters and gauges with snapshot copying.

Metrics answer questions such as:

- messages received
- captures committed
- validation failures
- queue depth
- reconnect count
- last successful heartbeat
- storage latency
- dropped or rejected callbacks
- lifecycle state

The copied snapshot matters because observability consumers should not mutate live runtime state.

```
Runtime metrics state
      │
      ▼ snapshot copy
Dashboard / logger
```

Observability reads the system; it does not own the system.

## Metrics are not authoritative state

A counter can be:

- reset at process restart
- delayed
- stale
- updated after an event
- inconsistent across processes
- missing due to a crash

Therefore, a metric such as:

```
captures_saved = 100
```

does not prove that exactly 100 durable capture files exist.

For authoritative recovery, inspect durable records and checkpoints. Metrics support diagnosis and trends.

This distinction mirrors:

```
evidence store = authoritative record
metrics         = operational observation
```

## Counter invariants

Metrics become more useful when related by invariants.

Example:

$$\text{received} \ge \text{preserved} \ge \text{validated} \ge \text{normalized} \ge \text{applied}$$

Differences have meaning:

```
received - preserved
    = possible evidence-loss boundary

preserved - validated
    = pending or rejected validation

validated - applied
    = pending, suppressed, or failed effects
```

The exact relation depends on concurrency, so snapshots may need a common epoch or quiescent point before strict comparisons are valid.

An invariant monitor should distinguish:

- temporary in-flight difference
- sustained abnormal divergence
- logically impossible counter relationship

## Status reporting

The repository's status model exposes separate fields such as:

- connected
- heartbeat health
- last message time
- messages received
- reconnect count
- protocol version
- TLS status

This is preferable to one synthetic status lamp.

However, dashboards should still present an operator-oriented summary based on explicit policy:

```
READY
DEGRADED
NOT_READY
FAILED
UNKNOWN
```

The summary must retain access to underlying evidence. Otherwise the operator cannot determine why a state was chosen.

## Health status is a snapshot with age

Every health report needs:

- observation time
- source
- age
- activation generation
- validity period

A cached green status from ten minutes ago is not current proof.

Health evidence should expire.

This is especially important when one task reports health for another task. The consumer must know whether it is reading a live observation or an abandoned value.

## Cross-industry equivalents

| Domain | Equivalent |
|---|---|
| Automotive | AUTOSAR watchdog manager supervises checkpoints across software entities before servicing the hardware watchdog |
| Aerospace | Built-in test, command-path monitoring, redundant health voting, and mode-dependent fault containment |
| Industrial PLCs | Watchdog timers, input quality flags, communication health bits, scan-cycle monitoring |
| Robotics | Node lifecycle states, topic deadline QoS, liveliness leases, sensor freshness supervision |
| Linux drivers | TX watchdogs, carrier state, queue-stall detection, device reset escalation |
| RTOS firmware | Supervisor tasks, task heartbeats, event counters, hardware watchdog gating, bounded restart policies |

## Design patterns demonstrated

- **Supervisor Pattern**: one authority interprets health evidence.
- **Watchdog Pattern**: missing progress triggers bounded recovery.
- **Lease Pattern**: heartbeat health expires unless renewed.
- **Circuit Breaker**: repeated failure prevents continuous attempts.
- **Exponential Backoff**: retry load is bounded.
- **Jitter Pattern**: synchronized clients are decorrelated.
- **Health Aggregator**: separate facts produce a policy-defined readiness state.
- **Snapshot Pattern**: observability receives stable copies.
- **Tri-State Logic**: unknown is distinct from healthy and unhealthy.

## Current repository strengths

The implementation correctly demonstrates:

- explicit heartbeat timeout state
- fail-closed behavior before the first heartbeat
- bounded exponential backoff
- bounded jitter
- injectable randomness for deterministic tests
- thread-safe metrics
- copied metric snapshots
- separate Production and Scenario status
- explicit unknown status fields
- refusal to invent an unverified health-message parser

## Current repository limitations

A production-grade evolution would need:

- monotonic heartbeat deadlines
- explicit heartbeat states
- application versus transport liveness separation
- activation-generation binding
- retry authorization and budgets
- interruptible backoff
- stable-operation reset rules
- supervisor integration
- watchdog checkpoint contracts
- bounded metric names
- metrics overflow policy
- atomic multi-counter snapshots
- status timestamps and expiration
- persistent reconnect history
- alarm latching and acknowledgement
- verified USGS heartbeat semantics

## Architecture review questions

1. TCP, TLS, authentication, and subscription are healthy, but no application heartbeat has ever been observed. Should readiness be `UNKNOWN`, `NOT_READY`, or `READY_WITHOUT_HEARTBEAT`? What evidence determines the answer?

2. Define the progress checkpoints required before the safety supervisor may service a hardware watchdog. Which tasks are mandatory, and which can be degraded without resetting the system?

3. A temporary broker outage lasts ten minutes. Design a retry budget and backoff-reset condition that avoids both a reconnect storm and unnecessary manual intervention.

4. Metrics report 10,000 received messages but only 9,998 durable captures. How should the supervisor distinguish two messages currently in flight from permanent evidence loss?

5. The application heartbeat is fresh, but capture-storage latency now exceeds the message freshness budget. What should the overall readiness state become, and should the receiver remain connected?
