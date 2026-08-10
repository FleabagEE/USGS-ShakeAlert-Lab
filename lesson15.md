 ## Lesson 15 — The Protocol Adapter as an Anti-Corruption Boundary

  I inspected the repository read-only. I made no changes, opened no network
  connections, attempted no authentication, and did not read or modify
  credential contents or runtime configuration.

  The repository does not contain explicitly numbered lesson notes, so I
  reconstructed the sequence from the accepted ADRs, implementation layers,
  tests, and commit history.

  ### Lessons 1–14 reconstructed

  The course has already covered these architectural concerns:

  1. Passive laboratory safety boundary and prohibited outputs
  2. Fail-closed startup and environment classification
  3. Host, repository, and privilege boundaries
  4. Configuration ownership and explicit authorization gates
  5. Credential isolation and identity-selection rules
  6. Endpoint discovery without assuming a protocol
  7. Protocol-neutral transport contracts
  8. Immutable native message envelopes
  9. Narrow transport callback boundaries
  10. Bounded FIFO handoff and single-worker processing
  11. Destination routing without transport coupling
  12. Native preservation before parsing or interpretation
  13. Validation, classification, normalization, and provenance
  14. Runtime lifecycle, draining shutdown, observability, and failure
     containment

  The next architectural gap is visible in the repository:

  - The core defines a protocol-neutral MessageTransport.
  - The runtime accepts MessageEnvelope objects.
  - The registry refuses unknown adapters.
  - The standalone Java diagnostic has discovered ActiveMQ OpenWire/JMS
    behavior.

  - But that protocol-specific implementation is not integrated into the
    protocol-neutral runtime.

  Therefore, the correct next concept is the protocol adapter boundary.

  ———

  # The concept

  A protocol adapter translates between an external communication system and the
  application’s internal contract.

  For this laboratory, its conceptual flow is:

  ActiveMQ/JMS callback
          │
          ▼
  OpenWire transport adapter
    - extracts exact payload bytes
    - copies verified metadata
    - records receipt time
    - maps lifecycle state
    - sanitizes failures
          │
          ▼
  MessageEnvelope
          │
          ▼
  RuntimeService.submit()

  This boundary is often called an anti-corruption layer. “Corruption” here does
  not mean damaged data. It means allowing an external library’s concepts,
  threading model, failure types, and assumptions to spread throughout the
  architecture.

  ## Why it exists

  The broker client speaks in concepts such as:

  - JMS Message, TextMessage, and BytesMessage
  - sessions and consumers
  - OpenWire connection state
  - client callbacks
  - broker destinations
  - provider-specific message identifiers
  - acknowledgment modes
  - vendor exceptions

  The application core should instead understand:

  - exact payload bytes
  - verified source metadata
  - receipt time and environment
  - accepted or rejected submission
  - transport-neutral lifecycle and failure categories

  The adapter is the only component that needs to understand both vocabularies.

  This lets the core preserve and process messages without becoming an ActiveMQ
  application.

  ## What problem it solves

  Without this boundary, protocol-specific details leak downstream.

  For example, a capture store might start accepting TextMessage, the router
  might inspect JMS destinations, and health monitoring might expose raw
  JMSException objects. Soon every component depends on the broker library.

  That produces several problems:

  - Core behavior cannot be tested without the broker client.
  - Changing protocol requires rewriting storage and routing.
  - Broker-owned threads begin performing application work.
  - Vendor exceptions can leak secrets or unstable implementation details.
  - A transport upgrade can alter application semantics unexpectedly.
  - Replay cannot reproduce input without manufacturing JMS objects.
  - Protocol discovery and domain interpretation become entangled.

  The adapter localizes those risks.

  ## What happens if it is removed

  There are two likely failure modes.

  First, the application becomes directly coupled to JMS/OpenWire. Protocol
  types spread through capture, routing, validation, and observability. The
  standalone receiver effectively becomes the architecture.

  Second, the application pretends the mapping does not matter. It passes
  incomplete payloads or guessed metadata into the runtime. This is subtler but
  more dangerous: the core appears protocol-neutral while relying on
  undocumented conversion behavior.

  In either case, architectural decisions such as acknowledgment timing, byte
  preservation, callback concurrency, and redelivery handling become implicit.

  ## Ownership and responsibility boundaries

  The adapter owns:

  - Client-library construction and teardown
  - Connection, session, consumer, and subscription lifecycle
  - Library callback registration
  - Conversion of supported message bodies to exact bytes
  - Extraction of verified transport metadata
  - Creation of MessageEnvelope
  - Submission to the runtime handoff
  - Translation of transport failures into sanitized categories
  - Callback accounting and quiescence reporting
  - Protocol-specific pause, reject, disconnect, or acknowledgment operations—
    but only once their semantics are verified

  The adapter does not own:

  - XML or event interpretation
  - Domain validation
  - Event-versus-health policy
  - Native record retention policy
  - Normalization
  - Business routing
  - Operational actions
  - Retry policy for application processing
  - Fabricating missing metadata
  - Deciding that callback return means successful processing

  The runtime owns whether it can accept an envelope. The adapter owns what
  protocol action follows an acceptance or rejection result.

  That last distinction is critical:

  > The runtime can say “queue full.” Only the adapter can translate that into a
  > protocol-correct response.

  ## Concurrency implications

  The external client library owns the callback thread. Therefore, that thread
  is borrowed time.

  The adapter callback should do only bounded work:

  1. Account for the callback.
  2. Extract or copy the supported body.
  3. Copy bounded metadata.
  4. Construct an envelope.
  5. Attempt a non-blocking runtime submission.
  6. Record the outcome.
  7. Return.

  It should not parse XML, write files, wait for the worker, perform retries, or
  call domain services.

  There are also shutdown races:

  shutdown requested
        │
        ├── stop new broker delivery
        ├── wait for callbacks already entered
        ├── declare callbacks quiescent
        └── allow runtime to drain accepted envelopes

  Closing the consumer does not automatically prove that no callback is still
  executing. The adapter therefore needs explicit in-flight callback accounting
  or a client-library guarantee supported by evidence.

  Another risk is reentrancy. A client library might invoke an exception
  callback while shutdown is holding adapter state. If shutdown and callbacks
  share locks carelessly, deadlock becomes possible.

  ## Failure behavior

  The adapter should distinguish failures by ownership.

  ### Mapping failure

  The broker supplied a message, but the adapter could not safely map it into
  the internal contract.

  Examples:

  - Unsupported message body type
  - Payload exceeds the verified limit
  - Invalid timestamp representation
  - Required metadata cannot be safely extracted

  This increments a mapping-failure counter. It must not be disguised as an
  application parsing error because the message never crossed the adapter
  boundary.

  ### Submission rejection

  A valid envelope was built, but the runtime was stopping, failed, or
  saturated.

  The adapter records the rejection. What happens to the broker delivery—leave
  unacknowledged, pause, recover, reject, or disconnect—must remain deferred
  until the protocol semantics are verified.

  ### Connection failure

  The external connection was lost or could not start.

  The adapter transitions its transport state and publishes a sanitized error.
  The runtime should not receive fabricated messages representing connection
  status.

  ### Unexpected adapter defect

  A programming error inside mapping or callback coordination should make the
  adapter fail visibly. Catching every exception and continuing indefinitely
  could silently lose deliveries.

  ### Capture or domain failure

  Once submission succeeds, later processing failures belong downstream. The
  adapter must not reinterpret those failures as connection problems.

  ## Resource bounds

  A robust adapter needs explicit bounds even though the downstream queue is
  already bounded.

  Relevant bounds include:

  - Maximum accepted payload size
  - Maximum number of metadata properties
  - Maximum metadata key and value sizes
  - Maximum concurrent callbacks
  - Maximum time allowed for callback quiescence during shutdown
  - Maximum connection-start duration
  - Maximum sanitized error length
  - Maximum rate or retention of diagnostic records
  - Fixed upper bound on retry/backoff state, if reconnect is later approved

  The current MessageEnvelope already bounds metadata to 32 entries, keys to 64
  characters, and string or byte values to 4096 bytes. That is good defensive
  architecture.

  However, the envelope does not currently impose a payload-size limit. The
  adapter or transport configuration must eventually enforce a verified maximum
  before allocating or copying an arbitrarily large broker message.

  A bounded queue alone does not bound memory:

  memory ≈ queue capacity × maximum envelope size

  A queue of 1,000 entries is not meaningfully bounded if an entry may contain
  an unbounded payload.

  ## Relevant design patterns

  ### Adapter pattern

  Converts a JMS/OpenWire interface into the internal MessageTransport contract.

  ### Anti-corruption layer

  Prevents vendor types, semantics, and terminology from entering the core.

  ### Ports and adapters

  MessageTransport is the inbound port. The OpenWire implementation is one
  adapter. A replay source or future verified transport can use another adapter.

  ### Dependency inversion

  The runtime depends on the stable transport abstraction. The protocol
  implementation depends on that abstraction, not the reverse.

  ### Immutable message

  MessageEnvelope is an immutable ownership-transfer object. Once created,
  downstream components cannot change the adapter’s record of receipt.

  ### State machine

  Transport lifecycle should move through explicit states such as:

  STOPPED → STARTING → RUNNING → STOPPING → STOPPED
                         │
                         └──────────────→ FAILED

  ### Bulkhead

  The queue separates the transport’s execution context from the application
  worker. Failure or latency downstream does not immediately consume the broker
  callback thread—until the queue saturates, at which point failure becomes
  explicit.

  ## Embedded/RTOS equivalent

  The equivalent is a hardware or protocol driver feeding an RTOS queue.

  For example, an interrupt service routine receives UART or CAN data:

  ISR / DMA completion
          │
          ▼
  driver adapter
    - snapshot bytes
    - timestamp
    - validate frame bounds
    - enqueue descriptor
          │
          ▼
  RTOS worker task

  The ISR must not perform domain decoding or block on storage. The driver
  translates peripheral registers and DMA buffers into an application-owned
  frame structure.

  A key embedded distinction is buffer ownership. The adapter must establish
  whether bytes are copied or whether ownership of a fixed buffer is
  transferred. Passing a pointer to a DMA buffer that hardware immediately
  reuses creates a race equivalent to retaining a broker-library message after
  its callback returns.

  ## Automotive equivalent

  This is comparable to an AUTOSAR communication stack boundary.

  A CAN, LIN, or Ethernet-specific layer receives frames and maps them to
  protocol data units. Application software components consume stable signals or
  PDUs without knowing controller register details.

  The adapter boundary owns:

  - Bus-controller interaction
  - Frame identifiers
  - Timing and reception indication
  - Buffer validity
  - Bus-off and transport errors

  It should not own vehicle behavior such as braking or steering decisions.

  Removing that separation lets bus-driver timing and vendor behavior
  contaminate safety functions, making timing analysis and component
  qualification much harder.

  ## Aerospace equivalent

  The equivalent is an avionics I/O partition or bus interface for ARINC 429,
  MIL-STD-1553, or SpaceWire.

  The adapter converts bus words or frames into bounded, timestamped application
  messages. It also prevents malformed or unexpected bus activity from crossing
  partition boundaries unchecked.

  Aerospace systems emphasize:

  - Deterministic upper execution time
  - Static memory allocation
  - Explicit data validity
  - Partition containment
  - Monotonic sequence tracking
  - No ambiguous recovery following partial receipt

  The architectural principle is the same: transport evidence crosses the
  boundary; transport machinery does not.

  ## Industrial/PLC equivalent

  This resembles an industrial communication function block or gateway mapping
  Modbus, EtherNet/IP, or PROFINET data into controller tags.

  The communication layer owns polling, connection state, protocol exceptions,
  and register layout. Control logic consumes typed and validity-qualified
  process values.

  A weak design writes raw communication values directly into control tags. A
  stronger adapter publishes:

  - Value
  - Quality or validity
  - Source
  - Timestamp
  - Update sequence

  For this laboratory, MessageEnvelope plays the role of that validity-qualified
  handoff, although it intentionally preserves native bytes rather than
  converting them immediately into control values.

  ## Linux kernel/driver equivalent

  The Linux equivalent is a device driver separating hardware operations from
  generic kernel interfaces.

  Examples include:

  - A network driver converting hardware descriptors into socket buffers
  - A block driver mapping controller completions into block-layer requests
  - A character driver exposing device data through a stable file interface

  The driver owns interrupts, DMA, device state, and hardware error translation.
  Higher layers own filesystems, protocols, and applications.

  An OpenWire adapter similarly converts client-library objects into
  application-owned envelopes. The callback is analogous to an interrupt or
  completion handler: bounded work, explicit ownership transfer, and no
  unrelated policy.

  ## Architectural weaknesses in the current repository

  The repository has the correct seam, but the discovered OpenWire
  implementation remains a standalone diagnostic rather than a registered
  transport adapter.

  Specific weaknesses are:

  - The Java receiver combines connection management, callback handling,
    credential handling, native capture, and diagnostics.

  - It bypasses the protocol-neutral RuntimeService.
  - Its callback performs filesystem capture, which can block the client-owned
    callback thread.

  - It has no bounded handoff queue between JMS receipt and disk I/O.
  - Payload size is not visibly bounded before full materialization.
  - Callback quiescence is not modeled as part of shutdown.
  - It waits indefinitely rather than participating in the runtime lifecycle.
  - Capture failure is logged, but the resulting delivery disposition remains
    intentionally unresolved.

  - The adapter registry contains no verified OpenWire factory, which is safe
    but leaves the architectural integration incomplete.

  - There is potential duplication between Java-native capture semantics and the
    Python core’s capture pipeline.

  These are not reasons to activate integration now. They identify the future
  architectural work once credentials, topic assignment, authorization, and
  protocol behavior are independently confirmed.

  ## Improvements

  A future design should:

  1. Implement one OpenWire adapter behind the existing MessageTransport
     contract.

  2. Keep JMS types entirely inside that adapter.
  3. Convert callbacks into immutable MessageEnvelope objects.
  4. Submit envelopes through the existing non-blocking bounded handoff.
  5. Move native capture off the JMS callback thread.
  6. Define explicit message-body and payload-size support.
  7. Account for callbacks in progress and prove quiescence during shutdown.
  8. Translate exceptions into bounded, sanitized transport errors.
  9. Keep acknowledgment policy separate until verified.
  10. Register the adapter only for an explicitly reviewed protocol and version.
  11. Test it using a fake client-library facade, without requiring a broker.
  12. Maintain the existing rule that no adapter is selected by default.

  The key architecture lesson is:

  > A protocol adapter does not merely make two APIs fit. It contains the
  > external system’s concurrency, ownership, failure, and lifecycle semantics
  > so the rest of the system does not inherit them.

  ## Architecture-review questions

  Please answer these before we proceed to Lesson 16:

  1. A JMS callback receives a valid 20 KB message, builds an envelope, but the
     runtime queue is full. Which component owns the decision to leave the
     delivery unacknowledged or disconnect—the runtime or the adapter? Why?

  2. Why is successful enqueue not equivalent to durable capture?
  3. Suppose the broker library exposes a mutable byte buffer that is reused
     after the callback returns. What ownership rule must the adapter enforce?

  4. Why should an unsupported JMS message-body type be classified as an adapter
     mapping failure rather than a domain parsing failure?

  5. During shutdown, the consumer has been closed but one callback remains in
     progress. Is the transport quiescent? What should happen before the runtime
     finishes draining?

  6. If queue capacity is 500 but payload size is unbounded, is memory usage
     bounded? Explain the resource equation you would use.

  7. Which protocol-specific facts may legitimately enter MessageEnvelope, and
     which kinds of vendor-specific objects must remain inside the adapter?

  8. The diagnostic receiver currently writes captures inside the JMS callback.
     Identify the main concurrency failure this can cause and propose the
     architectural handoff that fixes it.

  9. What evidence would you require before selecting an acknowledgment policy?
  10. Draw or describe the responsibility boundary among the OpenWire adapter,
     RuntimeService, native capture store, and parser.
