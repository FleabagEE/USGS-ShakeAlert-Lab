## Lesson 10: Duplicate, redelivery, and sequence classification

  The central component is SequenceTracker in src/shakealert_lab/validation.py.

  Its governing rule is:

  > Receiving the same event-related information twice does not mean the same thing happened twice.

  A passive receiver may observe:

  - a genuinely new event;
  - an exact duplicate;
  - a broker redelivery;
  - a newer update;
  - a stale update;
  - an out-of-order update;
  - a cancellation;
  - a heartbeat;
  - something that cannot be classified.

  Those outcomes require different handling.

  ## Why this module exists

  Message brokers generally provide delivery guarantees—not application-level uniqueness.

  Depending on the protocol and acknowledgement policy, the broker might deliver a message more than once:

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

  The second delivery is not necessarily an upstream duplicate. It may be the correct consequence of at-least-once delivery.

  Likewise, earthquake information evolves over time:

  Event A, sequence 1
  Event A, sequence 2
  Event A, sequence 3
  Event A, cancellation

  These are not duplicates. They are different states in one event history.

  Without a sequence classifier, downstream code may:

  - process the same message multiple times;
  - mistake redelivery for a new earthquake;
  - allow an older update to replace a newer update;
  - treat cancellation as ordinary data;
  - count heartbeats as events;
  - perform irreversible actions repeatedly.

  ## What happens if it is removed

  The receiver can still preserve messages, but it cannot reason about their relationship.

  That distinction is important:

  Capture store:
  “What records arrived?”

  Sequence tracker:
  “How are those records related?”

  Removing the tracker does not destroy evidence, but it makes downstream state unsafe.

  For a passive lab, that could produce incorrect dashboards or metrics. In an operational system, duplicate command execution can be hazardous.

  Examples include:

  - opening a valve twice;
  - repeating a motor movement;
  - deploying a mechanism twice;
  - issuing duplicate alerts;
  - allowing an old command to undo a newer command.

  ## The repository’s disposition model

  The module defines these outcomes:

   Disposition            Meaning
  ━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   NEW                    First recognized message or event state
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   EXACT_DUPLICATE        Identical payload observed again without broker redelivery evidence
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   REDELIVERY             Identical payload observed with the broker’s redelivery indicator
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   NEWER_UPDATE           Sequence number is greater than the latest accepted sequence
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   STALE_UPDATE           Sequence equals the latest sequence but payload differs
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   OUT_OF_ORDER_UPDATE    Sequence is older than the latest observed sequence
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   CANCELLATION           Message represents cancellation
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   HEARTBEAT              Message represents health/liveness traffic
  ─────────────────────  ─────────────────────────────────────────────────────────────────────
   UNKNOWN                Available evidence cannot support a stronger classification

  This is a classification vocabulary, not an action policy.

  For example:

  OUT_OF_ORDER_UPDATE

  does not itself mean:

  delete it

  It means downstream policy now has enough information to decide whether to:

  - preserve only;
  - quarantine;
  - display historically;
  - ignore for current state;
  - raise an alarm;
  - request reconciliation.

  ## Identity has multiple layers

  Professional systems distinguish at least four identities.

  ### 1. Delivery identity

  Identifies one broker delivery attempt.

  A redelivery may have a new local receipt timestamp while referring to the same broker message.

  ### 2. Message identity

  Identifies the logical message assigned by the producer or broker.

  This may be useful, but only if its trust and uniqueness contract are documented.

  ### 3. Content identity

  The repository calculates SHA-256 over the native payload bytes.

  Two payloads with the same digest are treated as byte-identical for practical purposes.

  ### 4. Event identity

  Identifies the evolving real-world entity—for example, one earthquake.

  Several different messages can share an event identity while carrying different sequence numbers.

  These identities answer different questions:

  Same delivery?  → transport question
  Same message?   → protocol question
  Same bytes?     → content question
  Same event?     → domain question

  Collapsing them into one “message ID” creates subtle bugs.

  ## How the current tracker works

  The repository maintains:

  - a set of previously observed payload hashes;
  - the latest sequence number for each event key.

  Its decision process is approximately:

  Previously observed payload digest?
      │
      ├─ yes + redelivery flag → REDELIVERY
      ├─ yes                  → EXACT_DUPLICATE
      │
      ▼ no
  Heartbeat?    → HEARTBEAT
  Cancellation? → CANCELLATION
  No event key or sequence? → NEW
  No prior event state?     → NEW
  Sequence > prior?         → NEWER_UPDATE
  Sequence = prior?         → STALE_UPDATE
  Sequence < prior?         → OUT_OF_ORDER_UPDATE

  This is intentionally protocol-neutral. The tracker does not parse earthquake XML or invent an event key. A protocol/domain layer must supply verified classification inputs.

  That preserves architectural ownership:

  Parser:
  extracts candidate fields

  Validator:
  establishes whether fields are trustworthy

  SequenceTracker:
  compares trusted identity and sequence evidence

  Policy:
  decides what downstream effects are allowed

  ## Why duplicate detection must happen after preservation

  A repeated message is still a real delivery observation.

  If duplicates are discarded before capture, you lose evidence about:

  - broker redelivery behavior;
  - acknowledgement failures;
  - reconnect boundaries;
  - producer duplication;
  - network instability;
  - timing between deliveries.

  Therefore:

  receive duplicate
        ↓
  preserve duplicate delivery
        ↓
  classify relationship
        ↓
  suppress duplicate downstream effects if required

  Deduplication should suppress repeated effects—not erase history.

  ## Idempotency

  The main embedded principle is idempotent effect handling.

  An operation is idempotent when performing it repeatedly produces the same externally visible state as performing it once.

  Unsafe command:

  increase valve opening by 5%

  If repeated, the valve moves again.

  Safer state command:

  set valve opening to 40%

  If repeated, the demanded state remains 40%.

  When message delivery is at-least-once, every downstream consumer must either:

  - be naturally idempotent;
  - use a persistent idempotency key;
  - participate in a transaction;
  - tolerate repeated execution safely.

  An in-memory SequenceTracker can improve classification, but it cannot guarantee exactly-once effects across process crashes.

  ## Exactly-once is an end-to-end property

  A broker cannot independently guarantee exactly-once physical behavior.

  Consider:

  Receiver sends actuator command
          │
  Actuator executes it
          │
  Receiver crashes before recording completion
          │
  Message is redelivered

  After restart, the receiver does not know whether execution occurred.

  Solving this requires coordination across:

  - message identity;
  - persistent receiver state;
  - acknowledgement ordering;
  - command execution;
  - device feedback;
  - recovery behavior.

  Professional engineers are suspicious of phrases like “exactly once” unless the complete failure boundary is defined.

  ## Sequence numbers are not timestamps

  A sequence number expresses logical order within a defined scope.

  A timestamp expresses time according to a clock.

  These are not interchangeable:

  sequence 42 at 10:00:05
  sequence 43 at 10:00:04

  Clock correction could make timestamps appear reversed while the protocol sequence remains authoritative.

  Conversely, sequence numbers may reset after:

  - producer restart;
  - event lifecycle reset;
  - account change;
  - protocol version change;
  - counter rollover.

  A sequence value is meaningful only with its scope:

  SequenceIdentity
  ├── producer identity
  ├── event identity
  ├── protocol/version
  ├── activation or epoch
  └── sequence number

  This is the same reason your runtime callbacks needed generation IDs in Lesson 6.

  ## Cancellation ordering

  The current repository checks duplicate status before cancellation classification. After that, a new cancellation is classified as CANCELLATION.

  A production design needs more state:

  ACTIVE
    │ cancellation accepted
    ▼
  CANCELLED

  Then it must define:

  - Can a newer update reactivate a cancelled event?
  - Is cancellation terminal?
  - Can cancellation arrive before the event?
  - Can an old cancellation arrive after a newer update?
  - Does cancellation have its own sequence number?
  - How long is terminal state retained?

  Without explicit answers, CANCELLATION is merely a label, not a safe state transition.

  ## Why in-memory tracking is insufficient

  The current tracker loses all history when the process restarts.

  After reboot:

  _hashes = empty
  _latest = empty

  A redelivered message may therefore be classified as NEW.

  For this laboratory foundation, native captures remain available for reconstruction. A production-grade tracker would need one of these approaches:

  - rebuild state by replaying durable captures;
  - persist compact sequence state transactionally;
  - rely on a documented broker replay boundary;
  - obtain an authoritative snapshot from the producer;
  - use epoch-scoped identifiers that make restart ambiguity explicit.

  The state must be committed in the correct order relative to capture and acknowledgement.

  ## A subtle current limitation

  The tracker adds a new payload digest to its seen set before completing all classification.

  That means classification mutates state.

  A professional design must decide what happens if downstream persistence fails after classification:

  digest marked seen
        │
  downstream state commit fails
        │
  message redelivered
        │
  tracker now calls it duplicate

  If “seen” means merely observed, this is correct.

  If “seen” means successfully processed, it is incorrect.

  The architecture should use separate concepts:

  observed
  preserved
  validated
  classified
  applied
  acknowledged

  One Boolean named processed cannot safely represent all of them.

  ## Bounded memory

  The current digest set grows without limit.

  In long-running firmware, unbounded state is a latent failure.

  A bounded design needs retention policy based on documented semantics:

  - retain entries for the broker redelivery window;
  - retain active events plus a terminal grace period;
  - partition by event;
  - use an LRU structure only if eviction consequences are acceptable;
  - persist terminal checkpoints;
  - expose capacity and eviction metrics;
  - fail detectably if correctness depends on state that cannot be retained.

  A Bloom filter could save memory, but its false positives might classify a genuinely new message as already seen. That may be unacceptable in a safety path.

  Memory efficiency cannot be separated from failure semantics.

  ## Partitioned workers and ordering

  Your Lesson 7 partition design belongs here:

  verified event identity
            │
            ▼
  stable partition function
       ┌────┼────┐
       ▼    ▼    ▼
     Q0/W0 Q1/W1 Q2/W2

  But the callback cannot safely hash an unverified event_id.

  The correct ordering is:

  bounded acquisition
        ↓
  durable capture
        ↓
  parse and validate event identity
        ↓
  partition by verified identity
        ↓
  sequence classification
        ↓
  event-state processing

  Until event identity is verified, messages need a bounded preclassification path.

  Also, changing the number of partitions changes the hash mapping. That usually requires a controlled generation transition or a consistent-hashing/state-migration strategy.

  ## Cross-industry equivalents

   Domain             Equivalent
  ━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   Automotive         AUTOSAR alive counters, freshness values, rolling counters, duplicate suppression
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────
   Aerospace          Command sequence counters, replay protection, telemetry frame counters, command execution histories
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────
   Industrial PLCs    Transaction numbers, scan-cycle state, monotonic batch sequence tracking
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────
   Robotics           Sensor frame sequence IDs, ROS message timestamps, command UUIDs, goal lifecycle tracking
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────
   Linux drivers      Network packet sequence numbers, DMA descriptor ownership, block request tags
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────
   RTOS firmware      Frame counters, epoch numbers, persistent command journals, idempotency tables

  Across all these systems, the same question appears:

  > Have I seen this evidence before, and did I merely observe it—or safely act on it?

  ## Design patterns demonstrated

  - State Machine: event updates move through explicit lifecycle states.
  - Idempotent Consumer: repeated delivery does not repeat unsafe effects.
  - Inbox Pattern: received identities are recorded before effects are applied.
  - Sequence Barrier: older state cannot replace newer accepted state.
  - Epoch/Generation Pattern: sequence identity is scoped across restart and reconstruction.
  - Event Sourcing: durable captures can reconstruct classifier state.
  - Policy/Mechanism Separation: classification reports relationships; policy chooses actions.

  ## Architecture review questions

  1. A message is durably captured and classified as NEWER_UPDATE, but downstream state persistence fails. When it is redelivered, should it be classified as duplicate, newer
     update, or unapplied update? Design the state model.

  2. The process restarts with 500,000 historical captures. Would you replay all captures, load a checkpoint, or ask the upstream system for a snapshot? Define how you prove that
     the reconstructed state is complete.

  3. A payload has the same event ID and sequence number as the current state but different bytes. Is STALE_UPDATE strong enough, or should this be treated as a protocol-integrity
     conflict?

  4. How would you bound duplicate history without allowing an old redelivery to become NEW after eviction?
  5. In your partitioned-worker design, the worker count changes from four to eight during an upgrade. How do you prevent one event’s messages from being processed concurrently by
     both the old and new partitions?
