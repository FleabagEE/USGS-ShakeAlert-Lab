## Lesson 5: transport/base.py — protocol-neutral lifecycle architecture

  The primary module is src/shakealert_lab/transport/base.py, supported by src/shakealert_lab/transport/registry.py.

  This layer answers:

  > What must every transport do, regardless of whether the implementation uses MQTT, OpenWire, or a future protocol?

  It deliberately does not answer:

  > How does a particular broker protocol work?

  That separation is the core architectural decision.

  ## Why it exists

  Transport libraries naturally try to become the center of an application.

  They bring their own:

  - Connection states
  - Callback threads
  - Reconnection behavior
  - Exceptions
  - Subscription semantics
  - Shutdown behavior
  - Logging
  - Retry policy
  - Client-specific message objects

  If the application adopts all those concepts directly, the client library becomes the architecture.

  This module reverses that dependency:

  Application-defined transport contract
                  ▲
                  │ implements
                  │
      MQTT adapter / OpenWire adapter
                  │
                  ▼
         Third-party client library

  The application owns the lifecycle model. The library is an implementation detail.

  ## What problem it solves

  The contract standardizes:

  - Lifecycle states
  - Connection observations
  - Message handoff
  - Shutdown deadlines
  - Callback quiescence
  - Failure reporting
  - Saturation counters
  - Atomic status snapshots
  - Adapter construction and selection

  That lets runtime orchestration operate without knowing which broker library is underneath.

  ## What would happen if it were removed

  Each adapter would define its own meaning for operations such as start() and stop().

  One adapter might consider “socket created” to mean started. Another might wait for authentication. Another might wait for subscription.

  Consequences:

  - Runtime startup ordering becomes protocol-specific.
  - Health monitoring compares incompatible states.
  - Shutdown cannot know whether callbacks have stopped.
  - Tests must understand every native client.
  - Raw exceptions and credentials can escape through status APIs.
  - Replacing the client library changes system behavior.
  - Reconnection policy becomes hidden inside adapters.
  - A default protocol might be selected accidentally.

  A transport abstraction is successful only if it standardizes semantics, not merely method names.

  ## Design pattern: ports and adapters

  MessageTransport and MessageSink are ports.

  Protocol implementations are adapters.

                       Application core
                    ┌────────────────────┐
  Inbound envelope  │                    │  lifecycle control
  ─────────────────►│    MessageSink     │◄─────────────────
                    │                    │
                    └────────────────────┘
                              ▲
                              │ MessageTransport
                              │
                      Protocol adapter
                              │
                      Native client library

  This is sometimes called hexagonal architecture.

  The important direction is that the core defines the interfaces. The external broker library does not.

  ## Two independent state dimensions

  The repository separates lifecycle state from connection state.

  ### Lifecycle state

  STOPPED
  STARTING
  RUNNING
  STOPPING
  FAILED

  This describes the adapter object itself.

  ### Connection state

  UNKNOWN
  CONNECTING
  CONNECTED
  DISCONNECTED
  DEGRADED

  This describes an observed external connection condition.

  These are not interchangeable.

  A transport can be:

  lifecycle = RUNNING
  connection = CONNECTING

  or:

  lifecycle = RUNNING
  connection = DISCONNECTED

  RUNNING does not mean authenticated, subscribed, healthy, or receiving messages. It means the adapter lifecycle has started and is capable of managing its transport
  responsibilities.

  This distinction prevents false readiness.

  ## Design pattern: explicit state machine

  The transport lifecycle is a state machine, not a collection of Boolean flags.

  Weak design:

  started = true
  connected = false
  failed = true
  stopping = true

  That permits contradictory combinations.

  State-machine design asks:

  - Which transitions are valid?
  - Which states are terminal?
  - Which calls are idempotent?
  - What evidence accompanies each transition?
  - What must be true before reporting completion?

  The repository makes FAILED latched. A failed adapter cannot quietly restart itself.

  That is important because an in-process restart could obscure:

  - Whether messages were accepted
  - Whether callbacks are still running
  - Whether acknowledgment occurred
  - Whether duplicate delivery is expected
  - Whether partially initialized client state remains

  Recovery requires reconstruction or process restart, creating a clean lifecycle boundary.

  ## Why start() does not mean “ready”

  The contract explicitly says returning from start() does not prove:

  - Connected
  - Authenticated
  - Authorized
  - Subscribed
  - Receiving messages

  This avoids one of the most common distributed-system mistakes:

  thread started → service ready

  Starting a mechanism and achieving operational readiness are different events.

  A complete receiver needs a readiness model such as:

  adapter started
        ↓
  TLS connected
        ↓
  authenticated
        ↓
  authorized
        ↓
  exact subscription established
        ↓
  callback path ready
        ↓
  listener ready

  The current connection-state model does not yet represent every one of these stages.

  ## Why shutdown receives an absolute monotonic deadline

  The caller supplies an absolute monotonic deadline.

  This is stronger than giving each component a relative timeout.

  Suppose shutdown has a total budget of five seconds:

  runtime stop begins
          │
          ├── transport consumes 2 seconds
          ├── worker consumes 2 seconds
          └── storage receives remaining 1 second

  If every component independently receives “five seconds,” total shutdown could take fifteen seconds.

  One absolute deadline preserves the system-level budget.

  Monotonic time is used because wall-clock corrections must not extend or shorten shutdown unpredictably.

  ## Deadline ownership

  The contract says the caller owns the deadline.

  The transport may tighten a repeated deadline but may not extend it.

  That prevents this failure:

  stop(deadline + 5 seconds)
  stop(deadline + 30 seconds)

  A later repeated call must not accidentally grant more shutdown time after the original safety decision.

  This is temporal monotonicity:

  > Once the system becomes more restrictive, repeated commands cannot make it less restrictive.

  ## Idempotent shutdown

  stop() is designed to tolerate repeated calls.

  This matters because shutdown may be requested simultaneously by:

  - An operator
  - A signal handler
  - A supervisor
  - A health monitor
  - A startup-failure cleanup path
  - A parent runtime

  Idempotence means those callers do not need fragile coordination merely to avoid stopping twice.

  In embedded systems, cleanup operations should generally be repeatable because failure paths rarely occur in the tidy sequence imagined during initial design.

  ## Callback quiescence

  Stopping the client connection does not automatically prove that callbacks have finished.

  A callback might already be running on another thread.

  The stop report therefore includes:

  - Whether callbacks are quiescent
  - How many remain in progress
  - Whether the adapter reached STOPPED, remains STOPPING, or is FAILED

  STOPPED has a strong invariant:

  callbacks_quiescent = true
  callbacks_in_progress = 0

  This turns “stopped” from a vague claim into a verifiable state.

  ## MessageSink as the backpressure boundary

  MessageSink.submit() has narrow semantics:

  - Return normally only if the envelope entered the bounded queue.
  - Reject if the runtime is no longer accepting work.
  - Distinguish queue saturation.
  - Do not imply durable storage or successful processing.

  This is important:

  submit accepted
      ≠ captured
      ≠ parsed
      ≠ acknowledged
      ≠ processed

  Each stage needs its own evidence.

  Your decision to prefer detectable failure means a saturation rejection must propagate into counters and service health rather than disappearing.

  ## Atomic sanitized snapshots

  TransportSnapshot is an immutable observation of local adapter state.

  It includes:

  - Lifecycle and connection state
  - Environment and connection identity
  - Callback counts
  - Accepted and rejected submissions
  - Saturation count
  - Mapping failures
  - Callback quiescence
  - Latest sanitized error

  The snapshot must not perform network or filesystem operations.

  Why?

  A status query must not change the system being observed. If /health triggers broker traffic or blocks on a socket, observability becomes part of the failure.

  The snapshot pattern gives monitoring a stable, side-effect-free view.

  ## Counter invariants

  The contract enforces relationships such as:

  queue_saturations <= submissions_rejected

  and:

  accepted + rejected + mapping failures <= callbacks received

  This is accounting, not merely telemetry.

  Counters form a conservation model:

  callbacks received
      ├── accepted
      ├── rejected
      ├── mapping failure
      └── still in progress

  When counters violate this relationship, the instrumentation itself is untrustworthy.

  World-class systems treat observability invariants with the same seriousness as functional invariants.

  ## Sanitized error objects

  The public transport contract does not expose native exceptions.

  Instead, it permits a constrained error record:

  - Category
  - Unqualified exception type
  - Short sanitized summary
  - UTC occurrence time

  This prevents an adapter from exposing:

  - Credential values
  - Endpoint strings
  - Native library objects
  - Raw broker messages
  - Headers
  - Payloads
  - Module internals

  The native exception may be useful locally, but it should not automatically become part of the application’s public state.

  ## Explicit registry and no default adapter

  TransportRegistry selects an adapter using an exact protocol/version pair.

  If no verified adapter is registered, creation fails.

  It does not say:

  unknown protocol → try MQTT

  That is an important safety decision. Guessing a protocol can cause:

  - Traffic to the wrong service
  - Authentication material sent through the wrong stack
  - Unexpected publishing behavior
  - Misleading evidence
  - Accidental Production contact

  No default is a fail-closed protocol policy.

  ## Design pattern: abstract factory and registry

  The registry maps a verified protocol identity to a factory.

  (protocol, version)
          │
          ▼
  registered factory
          │
          ▼
  configured adapter

  This separates selection from construction.

  It also creates a natural policy boundary: only reviewed adapters should be registered.

  ## Where these patterns appear

  Automotive:

  - CAN, LIN, FlexRay, Automotive Ethernet, and diagnostic transports implement common communication-manager interfaces.
  - Lifecycle and bus-state management remain separate from application signals.

  Aerospace:

  - Multiple data buses expose standardized channel lifecycle, health, counters, and message-delivery contracts.
  - Failed channels often latch until controlled reinitialization.

  Industrial PLCs:

  - EtherNet/IP, PROFINET, Modbus, and fieldbus drivers present common I/O and diagnostic states to the controller runtime.

  Robotics:

  - Sensor and actuator transports implement common lifecycle and message interfaces while hiding vendor SDK callbacks.

  Linux kernel drivers:

  - Driver subsystems define operations that hardware-specific drivers implement.
  - Network interfaces separate administrative state, carrier state, queue state, and device failure.

  RTOS firmware:

  - UART, CAN, SPI, radio, and network drivers implement common start/stop/send/receive contracts.
  - ISR callbacks hand data to bounded RTOS queues while health is reported through atomic state.

  ## Architectural weaknesses to notice

  ### Lifecycle and connection combinations are not fully constrained

  The snapshot can theoretically represent contradictory combinations such as:

  state = STOPPED
  connection_state = CONNECTED

  The current validation checks individual fields and counters but not the full state product.

  A stronger model would define permitted combinations explicitly.

  ### Readiness is underspecified

  CONNECTED still does not distinguish:

  - TLS complete
  - Authentication complete
  - Authorization complete
  - Subscription established
  - First heartbeat received

  The Scenario work has demonstrated why those stages matter.

  ### Structural interfaces are not enforcement boundaries

  The protocol defines required behavior, but an implementation can still violate the documented semantics.

  Tests and reviews must verify:

  - Atomic snapshots
  - Deadline behavior
  - Callback quiescence
  - Sanitization
  - Idempotence
  - Failure latching

  An interface alone does not create correctness.

  ### The registry is mutable

  Factories can be registered at runtime, and the registry has no sealing step or authority check.

  A mature design might:

  1. Register approved adapters during composition.
  2. Validate the complete registry.
  3. Seal it.
  4. Refuse later mutation.

  ### Registration does not prove verification

  The class says “verified adapter,” but nothing cryptographically or structurally proves that a registered factory has been reviewed.

  The registration boundary should eventually require explicit authority or approved build composition.

  ### Error sanitization remains caller-dependent

  The error object constrains shape and length, but it cannot prove the summary contains no secret.

  A string can be short, trimmed, and still confidential.

  Adapters must construct summaries from allowlisted constants or a trusted classifier—not arbitrary native exception messages.

  ### OpenWire is outside the registry

  The Java Scenario receiver currently operates as a separate integration tool. It is not registered through this Python transport architecture.

  That is acceptable for bounded discovery, but live architecture remains split until OpenWire is integrated behind the same lifecycle and safety contract.

  ## Your design questions

  1. Define the permitted combinations of TransportState and connection/readiness state. Can FAILED still be connected? Can STOPPING be degraded? What evidence decides?
  2. Design a readiness model that distinguishes TLS, authentication, authorization, and subscription without creating dozens of contradictory Booleans.
  3. An expired shutdown deadline arrives while two callbacks are still executing. What must stop() do immediately, what must it report, and who owns eventual cleanup?
  4. How would you seal TransportRegistry so only authorized adapter factories can exist in the operational composition?
