## Lesson 4: MessageEnvelope — the concurrency and trust boundary

  The central module is src/shakealert_lab/messaging/inbound.py.

  Its purpose is to turn a transport-owned callback object into an application-owned, immutable record.

  Broker library object
          │
          │ copy and validate
          ▼
  MessageEnvelope
          │
          ├── queue
          ├── capture
          ├── routing
          ├── validation
          └── replay

  This boundary controls ownership, mutability, time, provenance, resource consumption, and transport coupling.

  ## Why it exists

  Messaging libraries often invoke application callbacks on library-owned threads. The object passed to the callback may:

  - Belong to the transport library
  - Be valid only during the callback
  - Contain mutable buffers
  - Be reused after callback return
  - Expose protocol-specific methods
  - Perform network operations when accessed
  - Have undocumented thread-safety
  - Change state when acknowledged or decoded

  Passing that native object into a worker queue would transfer something the application does not actually own.

  MessageEnvelope creates an owned snapshot before the callback returns.

  ## What problem it solves

  It gives every downstream component one stable contract:

  - Exact payload bytes
  - Local receipt time
  - Declared source environment
  - Connection identity
  - Optional transport metadata
  - Bounded verified metadata

  The parser does not need to understand MQTT objects. Storage does not need JMS classes. Replay does not need a live broker connection.

  Transport-specific uncertainty stops at the adapter boundary.

  ## What would happen if it were removed

  Native transport objects would leak into the rest of the architecture.

  Likely consequences:

  - Domain code becomes coupled to a specific broker library.
  - Library-owned buffers could mutate after enqueue.
  - Tests require transport-library mocks everywhere.
  - Replay requires reconstructing fake broker objects.
  - Storage might accidentally invoke network-backed methods.
  - Worker threads could violate the client library’s thread rules.
  - Switching protocols would require rewriting parsing, routing, and storage.
  - A callback object might be acknowledged or invalidated before durable capture.

  The envelope is therefore not just a data container. It is a firewall between two ownership domains.

  ## Embedded principle: establish ownership before concurrency

  A professional concurrency design asks:

  > Who owns this object after the callback returns?

  Possible answers include:

  - The transport library retains ownership.
  - The callback transfers ownership.
  - The application creates an independent copy.
  - Ownership is shared under a lifetime protocol.

  This repository chooses an independent immutable copy.

  That is the safest choice when the native library’s lifetime rules are unverified.

  ## Design pattern: message envelope

  The envelope pattern wraps a payload with the context needed to process it correctly.

  MessageEnvelope
  ├── payload
  ├── receipt provenance
  ├── source environment
  ├── transport identity
  ├── delivery identity
  ├── timing information
  └── verified metadata

  The payload answers, “What bytes arrived?”

  The envelope answers:

  - Where did they arrive?
  - When did they arrive?
  - Through which connection?
  - From which environment?
  - Were they marked as redelivered?
  - Which destination carried them?
  - Which metadata has been verified?

  Payload without provenance is often operationally meaningless.

  ## Design pattern: anti-corruption layer

  Each transport adapter translates its native representation into MessageEnvelope.

  Downstream modules depend on the repository’s contract rather than the broker library’s contract.

  MQTT object ──┐
                ├──► MessageEnvelope ──► application
  JMS object  ──┘

  This prevents transport terminology and behavior from contaminating domain logic.

  It also allows different transports to coexist without pretending their semantics are identical. Unsupported or unverified metadata can remain absent.

  ## Why the payload must be exact immutable bytes

  The module rejects strings, byte arrays, memory views, and other mutable or interpreted forms.

  This decision preserves three properties.

  ### Fidelity

  The system stores what arrived, not what a decoder thinks arrived.

  Malformed encoding remains evidence.

  ### Immutability

  A mutable buffer cannot change after hashing, routing, or enqueueing.

  ### Parser independence

  Different parsers can interpret the same captured bytes later. A parser correction does not require reacquiring the original message.

  This supports the repository’s governing rule:

  > Preserve first; interpret second.

  ## Why interpretation must not happen in the callback

  The accepted callback decision is deliberately narrow:

  1. Copy native data.
  2. Construct the envelope.
  3. Submit it to the handoff.
  4. Return.

  The callback must not perform:

  - Parsing
  - Storage
  - Routing
  - Retry logic
  - Domain processing
  - Publishing

  The callback belongs to the transport’s timing domain. Slow work there can delay heartbeats, block socket reads, trigger disconnects, or prevent other messages from being
  delivered.

  This is the same reason interrupt handlers and RTOS ISRs do minimal work.

  Interrupt/callback context
      └── capture minimum state and defer work

  Worker context
      └── perform expensive or blocking operations

  ## Time is part of the message contract

  received_at_utc must be timezone-aware and explicitly UTC.

  This prevents several ambiguous states:

  - Local time interpreted as UTC
  - Daylight-saving transitions
  - Timestamps from different zones compared directly
  - Naive datetimes silently inheriting host assumptions

  The local receipt timestamp and broker timestamp are kept separate because they represent different events:

  - received_at_utc: when this process observed the message
  - server_timestamp: a timestamp supplied through the transport

  They should never be treated as interchangeable.

  ## Environment includes UNKNOWN

  Configuration rejects UNKNOWN for an active endpoint, but message evidence can still be unknown or conflicting.

  That distinction is intentional:

  - A configured receiver must know which environment it intends to connect to.
  - An observed message may fail to prove which environment it actually belongs to.

  A good evidence system never upgrades uncertainty merely because configuration expected a particular result.

  ## Verified metadata is deliberately constrained

  Metadata is restricted by:

  - Maximum entry count
  - Maximum key length
  - Maximum string/byte size
  - Small set of immutable scalar types
  - Defensive copying
  - Read-only exposure

  This prevents metadata from becoming an unbounded escape hatch.

  Without these limits, a small payload could carry enormous headers, deeply nested objects, native library handles, mutable lists, or unserializable values.

  The field is named verified_metadata, not merely metadata. That is an epistemic label: entries should represent facts the adapter understands well enough to assert.

  ## Defensive copying

  The caller’s metadata mapping is copied before being exposed.

  Otherwise:

  adapter creates envelope
          │
          ▼
  adapter later mutates original dictionary
          │
          ▼
  message history silently changes

  The mapping proxy prevents downstream mutation, while the allowed values are themselves immutable.

  This establishes snapshot semantics: the envelope describes the callback event as it existed at construction time.

  ## Derived size and digest

  Payload size and SHA-256 are derived from the payload rather than supplied independently.

  That avoids contradictory state such as:

  payload = 100 bytes
  reported size = 95 bytes

  The digest provides a stable content identity for later integrity checks and provenance.

  This is a general design principle:

  > Do not store independently mutable values that can be derived from one authoritative source.

  ## Exact-destination routing

  The related src/shakealert_lab/messaging/router.py uses exact destination matching.

  It does not guess, use partial matches, or route unknown destinations to a default operational handler.

  Unknown or missing destinations produce an explicit failure.

  That is the routing equivalent of fail-closed authorization.

  ## Where this pattern appears

  Automotive:

  - CAN receive interrupts copy frame ID, DLC, payload, hardware timestamp, and status into an application-owned frame object.
  - Decoding and signal processing occur later in task context.

  Aerospace:

  - Bus frames are captured with channel, time, sequence, validity, and redundancy-source metadata before application interpretation.

  Industrial PLCs:

  - Fieldbus input images snapshot physical/network state at defined scan boundaries.
  - Logic consumes the stable image rather than reading changing hardware registers throughout the scan.

  Robotics:

  - Sensor middleware wraps payloads with frame identity, timestamp, sequence, and calibration context.
  - Workers process immutable message snapshots outside driver callbacks.

  Linux kernel drivers:

  - Interrupt handlers acknowledge hardware, capture minimal state, and schedule deferred processing through threaded interrupts, tasklets, workqueues, or NAPI.

  RTOS firmware:

  - ISRs copy bounded data into queues or ring buffers and wake worker tasks.
  - Ownership rules determine whether buffers are copied, pooled, or transferred.

  ## Architectural weaknesses to notice

  ### Payload size is not bounded here

  The configuration contains a maximum payload size, but MessageEnvelope itself accepts any byte length.

  That means its safety depends on every adapter enforcing the external limit before construction.

  A stronger design could require the limit at construction or use a validated payload factory.

  ### Hashing is recalculated

  Every payload_sha256 access hashes the complete payload again.

  For a large payload or repeated access, this becomes unnecessary CPU work. In a real-time system it may create timing variability.

  A capture boundary could calculate the digest once and bind it immutably to the payload.

  ### Receipt time lacks monotonic context

  UTC is correct for correlation and audit, but wall-clock time can jump when corrected.

  For latency and timeout measurement, the system should also capture a monotonic timestamp.

  Professional systems often carry both:

  UTC timestamp       → correlation and evidence
  monotonic timestamp → durations and ordering within one boot

  ### Some strings permit control characters

  connection_name rejects control characters, but several optional string fields validate only nonempty trimmed text.

  A malicious message ID or destination could contain embedded newline or terminal-control characters and later affect logs or diagnostics.

  All externally derived strings need an output-safety policy.

  ### Environment appears authoritative

  The envelope stores an Environment value, but that value normally originates from configured connection identity.

  It may represent intended provenance rather than independently proven provenance.

  A stronger model might distinguish:

  configured_environment
  observed_environment_evidence
  resolved_environment

  ### Verified and unverified metadata create tension

  Protocol discovery benefits from retaining unknown native headers. Safety benefits from refusing to promote unknown data into trusted application fields.

  A mature design may need two separate structures:

  native_metadata     → preserved exactly, treated as untrusted evidence
  verified_metadata   → bounded, typed facts safe for application logic

  Never use one field for both purposes.

  ### Queue handoff is not durable preservation

  After the envelope enters an in-memory queue, the callback may return—but the message has not necessarily reached durable storage.

  A process crash can still lose it.

  This creates a critical acknowledgment question:

  > Does the broker consider callback completion, enqueue, capture, or explicit acknowledgment to mean successful delivery?

  The repository correctly refuses to guess.

  ## Connection to your saturation decision

  You chose detectable failure over silent data loss.

  At this boundary, that implies queue saturation must produce evidence containing at least:

  - Connection identity
  - Saturation timestamp
  - Queue depth and capacity
  - Whether the current envelope was accepted
  - Whether the transport was paused or disconnected
  - Whether broker redelivery is expected
  - Whether message identity is available
  - Service transition, such as FAILED

  The system must never claim that a message was safely received merely because the callback observed it.

  ## Your design questions

  1. Suppose the transport gives the callback a mutable buffer that it reuses immediately after return. Where exactly must the copy occur, and who owns each buffer before and after
     the handoff?

  2. Would you place the maximum-payload check inside MessageEnvelope, in the transport adapter, or in a dedicated factory? Explain how your choice prevents every adapter from
     forgetting the check.

  3. Design the timestamp model. Which UTC, monotonic, broker, and sequence fields would you preserve, and which are allowed to drive timeout or ordering decisions?
  4. How would you preserve unknown native headers for forensic discovery without allowing them to influence routing, authorization, logging, or operational behavior?
