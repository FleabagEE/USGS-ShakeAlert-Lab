# Lesson 10: Duplicate, Redelivery, and Sequence Classification

The central component is `SequenceTracker` in `src/shakealert_lab/validation.py`.

Its governing rule is:

> Receiving the same event-related information twice does not mean the same thing happened twice.

A passive receiver may observe:

- A genuinely new event
- An exact duplicate
- A broker redelivery
- A newer update
- A stale update
- An out-of-order update
- A cancellation
- A heartbeat
- Something that cannot be classified

Those outcomes require different handling.

---

## Why This Module Exists

Message brokers generally provide delivery guarantees—not application-level uniqueness.

Depending on the protocol and acknowledgement policy, the broker might deliver a message more than once:

```text
Broker sends M
      │
      ▼
Receiver preserves M
      │
      ▼
Connection fails before ACK
      │
      ▼
Broker sends M again
```

The second delivery is not necessarily an upstream duplicate. It may be the correct consequence of at-least-once delivery.

Likewise, earthquake information evolves over time:

```text
Event A, sequence 1
  ↓
Event A, sequence 2
  ↓
Event A, sequence 3
  ↓
Event A, cancellation
```

These are not duplicates. They are different states in one event history.

Without a sequence classifier, downstream code may:

- Process the same message multiple times
- Mistake redelivery for a new earthquake
- Allow an older update to replace a newer update
- Treat cancellation as ordinary data
- Count heartbeats as events
- Perform irreversible actions repeatedly

---

## What Happens If It Is Removed

The receiver can still preserve messages, but it cannot reason about their relationship.

That distinction is important:

* **Capture store:** *"What records arrived?"*
* **Sequence tracker:** *"How are those records related?"*

Removing the tracker does not destroy evidence, but it makes downstream state unsafe.

For a passive lab, that could produce incorrect dashboards or metrics. In an operational system, duplicate command execution can be hazardous. Examples include:

- Opening a valve twice
- Repeating a motor movement
- Deploying a mechanism twice
- Issuing duplicate alerts
- Allowing an old command to undo a newer command

---

## The Repository’s Disposition Model

The module defines these outcomes:

| Disposition | Meaning |
| :--- | :--- |
| `NEW` | First recognized message or event state |
| `EXACT_DUPLICATE` | Identical payload observed again without broker redelivery evidence |
| `REDELIVERY` | Identical payload observed with the broker’s redelivery indicator |
| `NEWER_UPDATE` | Sequence number is greater than the latest accepted sequence |
| `STALE_UPDATE` | Sequence equals the latest sequence but payload differs |
| `OUT_OF_ORDER_UPDATE` | Sequence is older than the latest observed sequence |
| `CANCELLATION` | Message represents cancellation |
| `HEARTBEAT` | Message represents health/liveness traffic |
| `UNKNOWN` | Available evidence cannot support a stronger classification |

This is a **classification vocabulary**, not an action policy.

For example, `OUT_OF_ORDER_UPDATE` does not itself mean *"delete it"*. It means downstream policy now has enough information to decide whether to:

- Preserve only
- Quarantine
- Display historically
- Ignore for current state
- Raise an alarm
- Request reconciliation

---

## Identity Has Multiple Layers

Professional systems distinguish at least four identities:

### 1. Delivery Identity
Identifies one broker delivery attempt. A redelivery may have a new local receipt timestamp while referring to the same broker message.

### 2. Message Identity
Identifies the logical message assigned by the producer or broker. This may be useful, but only if its trust and uniqueness contract are documented.

### 3. Content Identity
The repository calculates SHA-256 over the native payload bytes. Two payloads with the same digest are treated as byte-identical for practical purposes.

### 4. Event Identity
Identifies the evolving real-world entity—for example, one earthquake. Several different messages can share an event identity while carrying different sequence numbers.

These identities answer different questions:
* **Same delivery?** → Transport question
* **Same message?** → Protocol question
* **Same bytes?** → Content question
* **Same event?** → Domain question

Collapsing them into one "message ID" creates subtle bugs.

---

## How the Current Tracker Works

The repository maintains:

- A set of previously observed payload hashes
- The latest sequence number for each event key

Its decision process follows this structure:

```text
Previously observed payload digest?
    │
    ├─ yes + redelivery flag ──> REDELIVERY
    ├─ yes ───────────────────> EXACT_DUPLICATE
    │
    ▼ no
Heartbeat? ───────────────────> HEARTBEAT
Cancellation? ────────────────> CANCELLATION
No event key or sequence? ────> NEW
No prior event state? ────────> NEW
Sequence > prior? ────────────> NEWER_UPDATE
Sequence = prior? ────────────> STALE_UPDATE
Sequence < prior? ────────────> OUT_OF_ORDER_UPDATE
```

This is intentionally protocol-neutral. The tracker does not parse earthquake XML or invent an event key. A protocol/domain layer must supply verified classification inputs.

That preserves architectural ownership:
* **Parser:** Extracts candidate fields
* **Validator:** Establishes whether fields are trustworthy
* **SequenceTracker:** Compares trusted identity and sequence evidence
* **Policy:** Decides what downstream effects are allowed

---

## Why Duplicate Detection Must Happen After Preservation

A repeated message is still a real delivery observation.

If duplicates are discarded before capture, you lose evidence about:

- Broker redelivery behavior
- Acknowledgement failures
- Reconnect boundaries
- Producer duplication
- Network instability
- Timing between deliveries

Therefore:

```text
Receive duplicate
       │
       ▼
Preserve duplicate delivery
       │
       ▼
Classify relationship
       │
       ▼
Suppress duplicate downstream effects if required
```

Deduplication should suppress repeated effects—not erase history.

---

## Idempotency

The main embedded principle is **idempotent effect handling**.

An operation is idempotent when performing it repeatedly produces the same externally visible state as performing it once.

* **Unsafe command:** `"Increase valve opening by 5%"` *(If repeated, the valve moves again.)*
* **Safer state command:** `"Set valve opening to 40%"` *(If repeated, the demanded state remains 40%.)*

When message delivery is at-least-once, every downstream consumer must either:

- Be naturally idempotent
- Use a persistent idempotency key
- Participate in a transaction
- Tolerate repeated execution safely

An in-memory `SequenceTracker` can improve classification, but it cannot guarantee exactly-once effects across process crashes.

---

## Exactly-Once is an End-to-End Property

A broker cannot independently guarantee exactly-once physical behavior. Consider:

```text
Receiver sends actuator command
              │
              ▼
    Actuator executes it
              │
              ▼
Receiver crashes before recording completion
              │
              ▼
     Message is redelivered
```

After restart, the receiver does not know whether execution occurred.

Solving this requires coordination across:

- Message identity
- Persistent receiver state
- Acknowledgement ordering
- Command execution
- Device feedback
- Recovery behavior

Professional engineers are suspicious of phrases like "exactly once" unless the complete failure boundary is defined.

---

## Sequence Numbers Are Not Timestamps

A **sequence number** expresses logical order within a defined scope. A **timestamp** expresses time according to a clock.

These are not interchangeable:

```text
sequence 42 at 10:00:05
sequence 43 at 10:00:04
```

Clock correction could make timestamps appear reversed while the protocol sequence remains authoritative.

Conversely, sequence numbers may reset after:

- Producer restart
- Event lifecycle reset
- Account change
- Protocol version change
- Counter rollover

A sequence value is meaningful only within its scope:

```text
SequenceIdentity
├── Producer identity
├── Event identity
├── Protocol / version
├── Activation or epoch
└── Sequence number
```

This is the same reason your runtime callbacks needed generation IDs in Lesson 6.

---

## Cancellation Ordering

The current repository checks duplicate status before cancellation classification. After that, a new cancellation is classified as `CANCELLATION`.

A production design needs more state:

```text
  ACTIVE
    │ cancellation accepted
    ▼
CANCELLED
```

Then it must define:

- Can a newer update reactivate a cancelled event?
- Is cancellation terminal?
- Can cancellation arrive before the event?
- Can an old cancellation arrive after a newer update?
- Does cancellation have its own sequence number?
- How long is terminal state retained?

Without explicit answers, `CANCELLATION` is merely a label, not a safe state transition.

---

## Why In-Memory Tracking is Insufficient

The current tracker loses all history when the process restarts.

After reboot:
* `_hashes = empty`
* `_latest = empty`

A redelivered message may therefore be classified as `NEW`.

For this laboratory foundation, native captures remain available for reconstruction. A production-grade tracker would need one of these approaches:

- Rebuild state by replaying durable captures
- Persist compact sequence state transactionally
- Rely on a documented broker replay boundary
- Obtain an authoritative snapshot from the producer
- Use epoch-scoped identifiers that make restart ambiguity explicit

The state must be committed in the correct order relative to capture and acknowledgement.

---

## A Subtle Current Limitation

The tracker adds a new payload digest to its seen set before completing all classification. That means classification mutates state.

A professional design must decide what happens if downstream persistence fails after classification:

```text
Digest marked seen
       │
       ▼
Downstream state commit fails
       │
       ▼
Message redelivered
       │
       ▼
Tracker now calls it duplicate
```

* If "seen" means merely *observed*, this is correct.
* If "seen" means *successfully processed*, it is incorrect.

The architecture should use separate concepts:

```text
Observed ──> Preserved ──> Validated ──> Classified ──> Applied ──> Acknowledged
```

One Boolean named `processed` cannot safely represent all of them.

---

## Bounded Memory

The current digest set grows without limit. In long-running firmware, unbounded state is a latent failure.

A bounded design needs a retention policy based on documented semantics:

- Retain entries for the broker redelivery window
- Retain active events plus a terminal grace period
- Partition by event
- Use an LRU structure only if eviction consequences are acceptable
- Persist terminal checkpoints
- Expose capacity and eviction metrics
- Fail detectably if correctness depends on state that cannot be retained

A Bloom filter could save memory, but its false positives might classify a genuinely new message as already seen. That may be unacceptable in a safety path. Memory efficiency cannot be separated from failure semantics.

---

## Partitioned Workers and Ordering

Your Lesson 7 partition design belongs here:

```text
     Verified event identity
                │
                ▼
    Stable partition function
          ┌─────┼─────┐
          ▼     ▼     ▼
         Q0/W0 Q1/W1 Q2/W2
```

But the callback cannot safely hash an unverified `event_id`. The correct ordering is:

```text
Bounded acquisition
         │
         ▼
Durable capture
         │
         ▼
Parse and validate event identity
         │
         ▼
Partition by verified identity
         │
         ▼
Sequence classification
         │
         ▼
Event-state processing
```

Until event identity is verified, messages need a bounded preclassification path.

Also, changing the number of partitions changes the hash mapping. That usually requires a controlled generation transition or a consistent-hashing/state-migration strategy.

---

## Cross-Industry Equivalents

| Domain | Equivalent |
| :--- | :--- |
| **Automotive** | AUTOSAR alive counters, freshness values, rolling counters, duplicate suppression |
| **Aerospace** | Command sequence counters, replay protection, telemetry frame counters, command execution histories |
| **Industrial PLCs** | Transaction numbers, scan-cycle state, monotonic batch sequence tracking |
| **Robotics** | Sensor frame sequence IDs, ROS message timestamps, command UUIDs, goal lifecycle tracking |
| **Linux Drivers** | Network packet sequence numbers, DMA descriptor ownership, block request tags |
| **RTOS Firmware** | Frame counters, epoch numbers, persistent command journals, idempotency tables |

Across all these systems, the same question appears:

> *"Have I seen this evidence before, and did I merely observe it—or safely act on it?"*

---

## Design Patterns Demonstrated

- **State Machine:** Event updates move through explicit lifecycle states.
- **Idempotent Consumer:** Repeated delivery does not repeat unsafe effects.
- **Inbox Pattern:** Received identities are recorded before effects are applied.
- **Sequence Barrier:** Older state cannot replace newer accepted state.
- **Epoch/Generation Pattern:** Sequence identity is scoped across restart and reconstruction.
- **Event Sourcing:** Durable captures can reconstruct classifier state.
- **Policy/Mechanism Separation:** Classification reports relationships; policy chooses actions.

---

## Architecture Review Questions

1. A message is durably captured and classified as `NEWER_UPDATE`, but downstream state persistence fails. When it is redelivered, should it be classified as duplicate, newer update, or unapplied update? Design the state model.
2. The process restarts with 500,000 historical captures. Would you replay all captures, load a checkpoint, or ask the upstream system for a snapshot? Define how you prove that the reconstructed state is complete.
3. A payload has the same event ID and sequence number as the current state but different bytes. Is `STALE_UPDATE` strong enough, or should this be treated as a protocol-integrity conflict?
4. How would you bound duplicate history without allowing an old redelivery to become `NEW` after eviction?
5. In your partitioned-worker design, the worker count changes from four to eight during an upgrade. How do you prevent one event’s messages from being processed concurrently by both the old and new partitions?
