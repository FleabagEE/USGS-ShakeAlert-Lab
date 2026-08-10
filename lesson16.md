 # Lesson 16 — Composition Root and System Assurance

  The final concept is the composition root: the single place where components
  are selected, connected, configured, and authorized to operate.

  Until now, we studied components independently:

  Transport adapter
          ↓
  Bounded runtime queue
          ↓
  Native capture
          ↓
  Validation and routing
          ↓
  Normalization / replay / observability

  Each component may be correct on its own while the assembled system is unsafe
  or incorrect. The composition root owns that assembly.

  ## Why it exists

  Individual modules cannot safely decide the overall system topology.

  For example:

  - A transport should not choose which capture store to use.
  - A parser should not create its own transport.
  - A capture store should not read credentials.
  - A command-line handler should not bypass safety checks and instantiate a
    receiver directly.

  - A configuration file should not automatically become authority to connect.

  The composition root makes these decisions explicit:

  configuration + authorization + registered implementations
                              │
                              ▼
                      composition root
                              │
                constructs and connects system
                              │
                              ▼
                  one reviewed runtime graph

  It is the architectural equivalent of a wiring harness: it determines which
  real components are connected, even though it does not implement their
  internal behavior.

  ## What problem it solves

  A system can have perfectly designed interfaces and still be assembled
  incorrectly.

  Examples include:

  - Connecting a production transport to a scenario configuration
  - Selecting an unverified adapter
  - Sending replay traffic toward an external destination
  - Constructing the transport before the runtime is ready
  - Starting a receiver without safety preflight
  - Using a parser before native capture
  - Connecting a normalized output to an operational actuator
  - Creating two workers when ordering assumes one
  - Using credentials from one account with another account’s destination
  - Accidentally replacing a bounded queue with an unbounded one

  The composition root prevents local components from making these global policy
  decisions.

  ## What happens if it is removed

  Composition does not disappear. It becomes distributed and implicit.

  Each module begins constructing its dependencies:

  parser creates store
  store creates logger
  receiver creates transport
  transport reads configuration
  transport loads credentials

  This creates hidden dependency graphs and multiple startup paths. Tests may
  exercise one graph while production builds another.

  The most dangerous result is an architectural bypass: a new entry point
  creates a receiver directly and avoids the safety, configuration, registry, or
  lifecycle controls used by the approved entry point.

  Without one composition root, it becomes difficult to answer:

  - Which components actually run?
  - Which implementation was selected?
  - Where did its authority come from?
  - Can any entry point bypass the safety gate?
  - Does shutdown occur in the reverse of startup?
  - Is the deployed system the same system that was tested?

  ## Ownership and responsibility boundaries

  The composition root owns:

  - Reading validated configuration
  - Invoking mandatory safety checks
  - Resolving explicit environment identity
  - Selecting registered implementations
  - Constructing queues, stores, routers, adapters, and runtime services
  - Injecting dependencies
  - Applying resource limits
  - Establishing startup and shutdown order
  - Refusing incomplete or inconsistent configurations
  - Producing a sanitized description of the assembled topology

  It does not own:

  - Protocol callback mechanics
  - Message parsing
  - File persistence details
  - Domain interpretation
  - Queue implementation
  - Credential contents
  - Retry algorithms
  - Business decisions

  A useful rule is:

  > The composition root chooses policies and implementations; components
  > execute their narrowly owned behavior.

  ## Configuration is not authorization

  This is especially important in the ShakeAlert laboratory.

  A configuration may describe:

  - A hostname
  - A protocol
  - A destination
  - An account reference
  - A queue capacity

  That does not prove the operation is authorized.

  Three different checks are required:

  1. Configuration validity: Is the requested topology structurally meaningful?
  2. Implementation availability: Is a reviewed adapter registered for that
     exact protocol and version?

  3. Operational authorization: Is this connection explicitly permitted now?

  These gates should not collapse into one boolean.

  For example, possessing production credentials is not authorization to connect
  to production. Likewise, a valid scenario configuration is not permission to
  try alternate accounts until one works.

  ## System assurance

  The composition root tells us what system was assembled. An assurance argument
  explains why that assembled system satisfies its architectural claims.

  An assurance claim should have this structure:

  Claim
    supported by:
      architectural decision
      implementation constraint
      verification evidence
      known limitation

  Example:

  Claim: The laboratory cannot silently select an unverified transport.

  Supporting evidence:

  - Transport selection requires protocol and version.
  - The registry has no default adapter.
  - An unknown combination fails closed.
  - Tests verify rejection of unregistered transports.
  - The composition root does not infer a protocol from a port number.

  Known limitation:

  - A future code change could register an adapter incorrectly, so review and
    regression tests remain necessary.

  Assurance is not the statement “the design is safe.” It is the traceable
  connection between a claim and evidence.

  ## Concurrency implications

  The composition root owns the topology that determines concurrency.

  It decides:

  - Number of transport instances
  - Number of callback sources
  - Queue capacity
  - Number of workers
  - Whether stores are shared
  - Which component coordinates shutdown
  - Whether replay and live receipt may coexist

  A component cannot reason about concurrency it does not know exists. For
  example, a capture store tested with one worker may become unsafe if
  composition silently creates four workers.

  The root must preserve the concurrency model established by the architecture:

  start:
  capture dependencies → router → worker → transport

  stop:
  transport → callback quiescence → worker drain → storage close

  Starting in dependency order and stopping in reverse dependency order is a
  general rule, but callbacks complicate the reverse sequence. The transport is
  not truly stopped until it can no longer enter the runtime.

  ## Failure behavior

  Composition failures should occur before external activity whenever possible.

  Examples:

  - Unknown transport: refuse startup.
  - Missing authorization: refuse startup.
  - Invalid queue capacity: refuse startup.
  - Environment mismatch: refuse startup.
  - Missing capture dependency: refuse startup.
  - Production plus replay topology: refuse startup.
  - Duplicate adapter registration: refuse startup.

  Once running, ownership remains layered:

  - Transport failure belongs to the adapter.
  - Queue saturation belongs to the runtime handoff and adapter response policy.
  - Capture failure belongs to persistence and may degrade or fail the pipeline.
  - Expected malformed input belongs to message-level handling.
  - Worker death belongs to runtime lifecycle.
  - Failure to stop cleanly belongs to lifecycle coordination.

  The composition root should report these failures, but it must not obscure
  their original owner.

  A major anti-pattern is catching every startup failure and attempting an
  alternate configuration. In a safety-conscious system, fallback can silently
  change identity, protocol, environment, or destination.

  ## Resource bounds

  The composition root is where local limits become a system-wide resource
  budget.

  Suppose:

  - Queue capacity is (Q)
  - Maximum payload size is (P)
  - Per-envelope metadata overhead is (M)
  - Maximum callbacks simultaneously mapping messages is (C)
  - Capture worker has one in-progress message

  A first-order inbound memory estimate is:

  [
  (Q + C + 1) \times (P + M)
  ]

  Additional budgets include:

  - Transport-client buffers
  - Parser working memory
  - Filesystem temporary-file space
  - Log throughput and retention
  - Thread stacks
  - Open file descriptors
  - Shutdown drain time
  - Replay rate and queue occupancy

  Bounding each component independently is insufficient if their combined worst
  cases exceed the platform budget.

  The root should either calculate or validate a coherent system budget. On a
  microcontroller this may be static linker-time allocation; on Linux it may
  involve configuration validation, service limits, and filesystem quotas.

  ## Relevant design patterns

  ### Composition root

  One controlled location constructs the object graph.

  ### Dependency injection

  Components receive dependencies rather than discovering or constructing them.

  ### Fail-fast configuration

  Invalid combinations are rejected before threads, files, or connections are
  started.

  ### Registry

  Only reviewed implementations may be selected. No fallback or default is
  inferred.

  ### Policy enforcement point

  Global safety and authorization rules are checked before capabilities are
  activated.

  ### State machine

  The assembled system has an explicit lifecycle, not merely a collection of
  objects with unrelated start methods.

  ### Assurance case

  Architectural claims are connected to decisions, implementation constraints,
  tests, and limitations.

  ### Defense in depth

  The composition root checks policy, while individual components still enforce
  their own local invariants. The root is not the sole safety mechanism.

  ## Embedded/RTOS equivalent

  In an RTOS product, this role is often performed by board initialization and
  task creation code.

  It determines:

  - Which drivers are enabled
  - Static queue depths
  - Task priorities and stack sizes
  - Which interrupt feeds which queue
  - Watchdog ownership
  - Startup sequencing
  - Safe-state behavior
  - Hardware revision compatibility

  A dangerous embedded design allows each task to initialize hardware
  independently. A stronger design has one initialization owner construct the
  system from a reviewed hardware and software configuration.

  Assurance evidence may include:

  - Static stack analysis
  - Worst-case execution-time analysis
  - Queue-depth analysis
  - Interrupt-priority review
  - Watchdog tests
  - Fault-injection results

  ## Automotive equivalent

  The equivalent is ECU software composition and system integration.

  AUTOSAR configurations connect software components, runnables, communication
  services, and hardware abstraction. The integration layer determines which
  signals reach which consumers and at what rates.

  System assurance includes:

  - Freedom from interference
  - Timing budgets
  - End-to-end protection
  - Safe-state transitions
  - Diagnostic coverage
  - Traceability from safety goal to implementation and test

  A correct braking component connected to the wrong wheel-speed signal is still
  an unsafe system. Composition is part of correctness.

  ## Aerospace equivalent

  The equivalent is partition, schedule, and I/O configuration in an integrated
  modular avionics system.

  The integration configuration establishes:

  - Which application occupies each partition
  - CPU time allocation
  - Memory boundaries
  - Communication ports
  - Bus mappings
  - Startup order
  - Health-monitor responses

  Aerospace assurance strongly emphasizes that the certified binary,
  configuration, and hardware combination must match the tested baseline.

  A module passing unit tests is not enough. Evidence must address the
  integrated configuration.

  ## Industrial/PLC equivalent

  The equivalent is the PLC hardware configuration and control-program
  deployment topology.

  It binds:

  - Physical input modules to process tags
  - Output tags to actuators
  - Network devices to controller data
  - Tasks to scan rates
  - Safety logic to safety-rated I/O
  - Communication loss to defined fallback behavior

  A function block may be correct while its I/O mapping is wrong. Industrial
  commissioning therefore verifies the assembled mapping, not just the control
  algorithm.

  For this laboratory, the analogous concern is proving that passive data paths
  cannot be accidentally bound to operational outputs.

  ## Linux kernel/driver equivalent

  The equivalent appears in device-tree data, platform-device registration,
  module parameters, and driver binding.

  The driver implements behavior, while system composition determines:

  - Which driver binds to which device
  - Address and interrupt resources
  - DMA regions
  - Clock and power dependencies
  - Initialization order
  - Security and namespace exposure

  An incorrect device-tree entry can make a correct driver access the wrong
  hardware address. The configuration and binding are therefore part of the
  system architecture.

  ## Current repository strengths

  The repository already demonstrates several strong composition decisions:

  - The transport registry has no default adapter.
  - Protocol and version selection are explicit.
  - Unknown transports fail closed.
  - Runtime and transport contracts are separated.
  - Production and scenario configurations are separated.
  - Replay uses an internal sink and has no external publisher.
  - The safety invariant applies to application entry points.
  - The runtime has explicit lifecycle states.
  - Startup and draining shutdown ordering are documented.
  - Operational outputs are absent.
  - Unconfirmed account assignments are not resolved by guessing.

  ## Current architectural weaknesses

  The primary weakness is that composition is still partly fragmented.

  The repository contains:

  - A protocol-neutral runtime
  - Framework-level receiver composition
  - An offline MQTT implementation
  - A standalone Java OpenWire diagnostic
  - Systemd service definitions
  - Safety preflight logic

  These pieces do not yet form one fully integrated, verified Scenario receiver
  topology.

  Additional weaknesses include:

  - Safety depends on every entry point remembering to invoke preflight.
  - The Java diagnostic is a separate entry path with its own lifecycle.
  - The composed memory budget is not documented quantitatively.
  - Payload limits are not yet evidence-based.
  - Acknowledgment and saturation response remain unresolved.
  - System-level callback quiescence is not integrated with runtime draining.
  - There is no captured live message evidence validating the complete mapping.
  - Broker credentials and topic assignment remain unresolved, so integration
    cannot be claimed.

  - Some framework modules are intentionally skeletal and therefore provide
    architectural seams rather than production-complete implementations.

  ## Improvements

  A mature final architecture would add:

  1. One canonical composition root for every receiver entry point.
  2. A capability-based safety gate that must be passed before a transport can
     be constructed.

  3. A verified OpenWire adapter registered only for its exact protocol/version.
  4. Explicit account, destination, and environment consistency checks.
  5. A documented end-to-end memory and storage budget.
  6. A single coordinated startup/shutdown state machine.
  7. Machine-verifiable checks preventing replay-to-external-output wiring.
  8. A sanitized startup manifest describing the selected topology.
  9. Integration tests covering component wiring and failure propagation.
  10. Traceability from every major architectural claim to tests and operational
     evidence.

  11. A deployment identity tying source revision, configuration revision,
     dependencies, and service definition together.

  12. A formal readiness gate distinguishing “implemented,” “tested offline,”
     “observed against Scenario,” and “authorized for production.”

  The final architectural principle of the course is:

  > A system’s architecture is not the set of components it contains. It is the
  > controlled composition of those components, their responsibility boundaries,
  > their interactions under concurrency and failure, and the evidence
  > supporting the resulting system claims.

  ## Final architecture-review questions

  1. Why is configuration validity different from operational authorization?
  2. If every individual component passes its unit tests, name three ways the
     assembled system could still be unsafe.

  3. Which component should select the OpenWire adapter, and why must the
     adapter not select itself?

  4. Describe the required startup and shutdown order for the complete receiver.
  5. Why is “no default adapter” an architectural safety property rather than
     merely a configuration preference?

  6. If a new CLI command constructs a transport directly without using the
     canonical composition root, what architectural problem has been introduced?

  7. Given a queue capacity, maximum payload size, concurrent callback count,
     and per-message overhead, how would you estimate worst-case inbound memory
     consumption?

  8. What evidence would be required to support the claim: “Every accepted
     broker delivery is preserved before acknowledgment”?

  9. Separate these four maturity claims:
      - Implemented
      - Tested offline
      - Verified against Scenario
      - Authorized for production

  10. Give a concise final architecture review of this laboratory: its strongest
     property, its largest unresolved risk, and the next decision that must
     remain blocked pending external evidence.
