 ## Lesson 2: config.py — turning claims into trusted state

  The module is src/shakealert_lab/config.py.

  ### Why it exists

  A configuration file is untrusted input.

  Even when written by your own team, it can be:

  - Incomplete
  - Stale
  - Corrupted
  - Copied from the wrong environment
  - Internally inconsistent
  - Filled with placeholders
  - Syntactically valid but operationally unsafe

  config.py is the boundary between arbitrary TOML input and the state the application is prepared to believe.

  Its output is not merely “parsed configuration.” It is a validated model of the receiver instance.

  ### What problem it solves

  Without this boundary, every downstream component would need to interpret raw configuration independently.

  One component might treat a missing authorization field as false. Another might treat it as absent but acceptable. A third might interpret "false" as truthy because it is a
  nonempty string.

  This module establishes one interpretation before initialization continues.

  It verifies:

  - Required values exist.
  - Placeholder values are rejected.
  - Strings have no accidental boundary whitespace.
  - Environment is explicitly scenario or production.
  - Ports and resource bounds are within permitted ranges.
  - Authorization is an actual Boolean.
  - Storage destinations are provided.
  - Credentials are represented as file references, not secret values.
  - Missing connection authorization defaults to false.

  The architectural objective is determinism: every component receives the same interpretation of deployment intent.

  ### What would happen if it were removed

  Downstream modules would consume dictionaries, strings, and defaults directly.

  Consequences would include:

  - Validation duplicated across components
  - Different meanings for missing values
  - Invalid limits reaching resource allocation
  - Scenario and Production identities being mixed
  - Credentials appearing directly in configuration objects
  - Components accepting template placeholders as real endpoint facts
  - Runtime failures occurring long after the original configuration error
  - Testing every component against every malformed configuration shape

  This is a recurring embedded-systems principle: reject an invalid state at the boundary nearest its source.

  ### Embedded principle: validate before activation

  Embedded software often has two configuration phases:

  External representation
          │
          ▼
  Parse and validate
          │
          ▼
  Trusted internal representation
          │
          ▼
  Initialize hardware or communications

  The important boundary is between the first and second representations.

  A professional system does not allow arbitrary external values to flow directly into:

  - Memory allocation
  - DMA setup
  - Actuator limits
  - Network connections
  - Task priorities
  - Watchdog intervals
  - Timing parameters

  This repository applies that principle to queue size, shutdown deadline, payload limit, environment, endpoint, and authorization.

  ### Design pattern: configuration gateway

  load_config() is a configuration gateway, or anti-corruption layer.

  It prevents the external configuration format from spreading through the system. Components consume LabConfig and EndpointConfig, not TOML tables.

  This has an important consequence:

  > The rest of the application should not need to know that TOML exists.

  Changing the external format should affect the gateway, not the receiver architecture.

  ### Design pattern: immutable value objects

  The configuration becomes a collection of immutable value objects:

  - CredentialPaths
  - EndpointConfig
  - LabConfig

  The engineering purpose is temporal stability.

  After validation, another component cannot casually change the destination, environment, queue capacity, or authorization flag behind the receiver’s back.

  This supports a useful reasoning model:

  validated once
        │
        ▼
  unchanged during this receiver instance

  Without immutability, every use becomes a potential race with configuration mutation.

  ### Design pattern: aggregate configuration

  LabConfig is an aggregate describing one complete receiver instance:

  LabConfig
  ├── EndpointConfig
  │   ├── environment
  │   ├── transport facts
  │   ├── destination
  │   ├── credential references
  │   └── authorization flag
  ├── storage boundaries
  └── runtime resource bounds

  The aggregate prevents the receiver from being initialized with half of one configuration and half of another.

  This is especially important during reloads. A professional design swaps one complete, validated snapshot for another; it does not mutate fields incrementally while the system is
  running.

  ### Secret-by-reference design

  The module stores credential paths, not credentials.

  That separates:

  - Configuration metadata: where an authorized adapter may obtain a secret
  - Secret material: the protected value itself

  This reduces accidental exposure through:

  - Configuration logging
  - Object inspection
  - Exception formatting
  - Metrics
  - Debug dumps
  - Serialization

  It does not eliminate exposure—the adapter must eventually read the secret—but it narrows where that can happen.

  ### Resource bounds are architectural decisions

  The queue capacity, maximum payload, and shutdown deadline are not tuning trivia.

  They express failure policy:

  - Maximum payload bounds memory and storage exposure.
  - Queue capacity bounds backlog and latency.
  - Shutdown timeout prevents infinite draining.
  - Port validation prevents structurally invalid network configuration.

  In embedded systems, every configurable number should trigger two questions:

  1. What resource does this value consume?
  2. What happens at the boundary?

  A positive queue size is necessary, but not sufficient. The architecture must still define what happens when the queue is full.

  ### Where this pattern appears

  Automotive:

  - Calibration and coding data are loaded from flash, range-checked, cross-checked against vehicle variant, then exposed as a stable runtime dataset.
  - Invalid torque or current limits must prevent activation.

  Aerospace:

  - Mission and navigation data are validated for version, vehicle identity, checksums, bounds, and compatibility before becoming active flight data.

  Industrial PLCs:

  - Recipes and machine parameters are validated before being transferred into active control registers.
  - A syntactically valid recipe can still be incompatible with installed tooling.

  Robotics:

  - Robot description, joint limits, controller gains, sensor frames, and network parameters are validated before controllers activate.
  - A valid number with the wrong unit can be more dangerous than an invalid number.

  Linux kernel drivers:

  - Device Tree, ACPI, PCI descriptors, and module parameters are converted into driver state during probe.
  - Probe fails and unwinds if resources or identities are inconsistent.

  RTOS firmware:

  - Board configuration, NVM calibration, task periods, stack sizes, peripheral assignments, and communication addresses are validated before the scheduler enables dependent
    functions.

  ### Where this repository is still weak

  A world-class review should identify several limitations.

  #### Authorization is still a Boolean

  connect_authorized lives in the same editable file as ordinary configuration.

  That does not prove:

  - Who authorized it
  - What endpoint was authorized
  - Which topic was approved
  - When approval expires
  - Whether approval has been revoked
  - Whether the configuration changed after approval

  This is weaker than your proposed SafetyAuthority.

  #### Local validation is not evidence validation

  The loader can prove that a hostname is a trimmed nonempty string. It cannot prove that the hostname came from USGS.

  It can prove that the port is numerically valid. It cannot prove that it is the authorized Scenario port.

  This distinction is fundamental:

  structurally valid ≠ operationally verified

  #### Cross-field invariants are limited

  The module validates fields mostly in isolation.

  It does not prove relationships such as:

  - A Scenario environment must use a Scenario hostname.
  - A Scenario receiver must not reference Production credentials.
  - The topic belongs to the configured account.
  - TLS must always be enabled for an external endpoint.
  - Storage paths for Scenario and Production are disjoint.
  - Authorization applies to the exact endpoint tuple.

  World-class configuration systems validate relationships, not just fields.

  #### No provenance or integrity mechanism

  The configuration has no signature, approval digest, version identity, or tamper-evident relationship with authorization.

  A stronger design could authorize a digest over:

  environment
  host
  port
  protocol
  destination
  credential identity
  permissions
  expiration

  Changing any authorized field would invalidate the authority.

  #### The Java receiver bypasses this model

  The passive Java receiver currently hard-codes its endpoint and topic and accepts credential paths from arguments. It does not consume LabConfig.

  That helped constrain a focused integration test, but it creates two sources of truth. A mature architecture should either bring the Java receiver behind the validated
  configuration gateway or generate its immutable launch specification from that gateway.

  ## Your design questions

  1. Design a SafetyAuthority object. What exact fields must it bind together so authorization for Scenario cannot accidentally authorize Production?
  2. Which cross-field invariants would you add to LabConfig before permitting an external connection?
  3. Suppose configuration reload is required without stopping the process. Which changes can be applied live, and which must force the receiver to disconnect and reconstruct
     itself?

  4. The queue capacity is bounded, but the configuration does not specify overflow policy. For an earthquake-message receiver, would you block, reject newest, drop oldest,
     disconnect, or fail the service? Defend your choice.

  Answer those, and then we’ll study the credential boundary: src/shakealert_lab/credentials.py.
