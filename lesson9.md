## Lesson 9: Validation as a trust boundary

  The central module is src/shakealert_lab/validation.py, composed into the receive path by src/shakealert_lab/receiver.py.

  Its governing rule is:

  > Preservation records what happened; validation decides what may happen next.

  Untrusted message
        │
        ▼
  Durable native capture
        │
        ▼
  Validation boundary
     ┌──┴───┐
   valid   invalid
     │       │
  process   quarantine/report

  An invalid message remains valid evidence. It is merely inadmissible for downstream decisions.

  ### Why validation exists

  Transport success proves very little.

  A broker may successfully deliver a message that is:

  - too large;
  - too old or implausibly future-dated;
  - malformed;
  - duplicated;
  - out of sequence;
  - addressed incorrectly;
  - internally inconsistent;
  - valid according to an obsolete schema;
  - authentic but inappropriate for this application state.

  The transport adapter should report what arrived. It should not decide what the message means or whether the application may act on it.

  Validation creates a controlled transition from transport facts to application-approved data.

  ### What happens if validation is removed

  Without this boundary, every downstream consumer must defend itself independently:

  Parser A checks size
  Parser B assumes size is safe
  Logger truncates it
  Dashboard accepts it
  Operational logic trusts its timestamp

  That produces inconsistent trust decisions.

  One malformed input might be rejected by one module and accepted by another. In safety systems, this is called a split-brain interpretation: components disagree about the validity
  of the same evidence.

  A centralized validation policy gives every downstream component the same answer.

  ## The repository’s current validator

  CaptureValidator currently checks three protocol-neutral invariants:

  - payload size is within a configured maximum;
  - local receive time is not implausibly in the future;
  - server timestamp is not implausibly in the future.

  Its result is a structured ValidationResult, not merely a Boolean:

  ValidationResult
  ├── valid
  └── issues
      ├── stable issue code
      └── safe diagnostic detail

  This matters because “invalid” alone is insufficient for engineering decisions.

  A payload-size violation and a clock violation require different responses:

  - oversized payload could indicate configuration error, protocol drift, or resource attack;
  - future local receipt time suggests a local clock or timestamping defect;
  - future server time may indicate remote clock error or incorrect metadata interpretation.

  The issue code is intended for machines. The detail is intended for operators.

  ## Why validation happens after capture

  The repository’s PreservingRouter imposes this order:

  create capture
      ↓
  store capture
      ↓
  validate capture
      ↓
  send capture + validation result downstream

  This is one of the most important decisions in the repository.

  Suppose a 10 MB payload violates a 1 MB policy.

  If size validation happens before preservation:

  oversized → reject → no evidence

  If preservation happens first:

  oversized → preserve → reject downstream processing

  However, this creates a real tradeoff: a malicious sender could consume storage with oversized messages. A production architecture therefore needs two different size limits:

  1. Transport acquisition ceiling
     The absolute amount of data the system can safely receive without exhausting bounded memory.

  2. Application admissibility limit
     The smaller verified size allowed into normal interpretation.

  wire input
     │
     ├─ exceeds hard acquisition ceiling → terminate safely
     │
     ▼
  native capture
     │
     ├─ exceeds application limit → preserve and quarantine
     │
     ▼
  normal processing

  The acquisition ceiling protects the machine. The application limit protects the software semantics.

  ## Validation versus parsing

  These are different responsibilities.

  Validation asks:

  > Is this evidence admissible under a defined policy?

  Parsing asks:

  > How is the encoded structure represented?

  A parser can successfully decode an unsafe message. For example, valid XML might contain:

  - an impossible sequence number;
  - an unauthorized environment;
  - a timestamp outside policy;
  - conflicting event identifiers;
  - an unsupported message type.

  Likewise, a validation stage can perform useful envelope checks without understanding XML at all.

  A professional pipeline separates layers:

  Envelope validation
         ↓
  Structural parsing
         ↓
  Schema validation
         ↓
  Semantic validation
         ↓
  State-dependent validation
         ↓
  Authorized application behavior

  ### Layer 1: Envelope validation

  Checks facts available without decoding the payload:

  - payload bounds;
  - verified destination;
  - protocol identity;
  - receipt timestamp;
  - required transport metadata.

  This is approximately where the current CaptureValidator operates.

  ### Layer 2: Structural parsing

  Determines whether the bytes can be represented as the expected format.

  Examples:

  - well-formed XML;
  - decodable binary frame;
  - valid length fields;
  - bounded nesting.

  ### Layer 3: Schema validation

  Determines whether required fields and types satisfy a specific protocol version.

  ### Layer 4: Semantic validation

  Checks relationships between fields:

  - cancellation refers to an event;
  - magnitude lies within the supported domain;
  - coordinates are physically meaningful;
  - sequence numbers satisfy their contract.

  ### Layer 5: State-dependent validation

  Checks the message against prior system state:

  - is this a newer update?
  - was this event already cancelled?
  - is this an exact duplicate?
  - does it belong to the current activation generation?

  No single validator should silently collapse all five layers into “bad message.”

  ## Fail closed without losing evidence

  “Fail closed” does not always mean “crash immediately.”

  For an individual invalid message, it means:

  No validated authorization
            ↓
  No downstream operational effect

  The evidence can still be:

  - stored;
  - counted;
  - quarantined;
  - replayed offline;
  - inspected through secret-safe diagnostics.

  This gives two independent outcomes:

   Evidence outcome       Processing outcome
  ━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━
   Preserved              Accepted
  ─────────────────────  ──────────────────────────
   Preserved              Rejected
  ─────────────────────  ──────────────────────────
   Preservation failed    System failure
  ─────────────────────  ──────────────────────────
   Not yet preserved      Must not be acknowledged

  The dangerous combination is:

  not preserved + treated as successfully processed

  ## Policy versus mechanism

  The validator is a mechanism for applying rules. It should not invent those rules.

  For example, the one-second future tolerance is a policy choice. Its correct value depends on:

  - clock synchronization guarantees;
  - expected network delay;
  - timestamp definition;
  - USGS protocol documentation;
  - leap-second handling;
  - system safety requirements.

  A firmware architect asks:

  - Who authorized this limit?
  - In what units and time domain is it defined?
  - Can configuration change it?
  - Is changing it safety-relevant?
  - Does changing it require reconstructing the receiver?
  - How is the deployed value recorded with the evidence?

  A limit without provenance is merely a number in software.

  ## Time is not owned by one clock

  Your Lesson 5 answer—“nobody owns time; keep multiple clocks”—becomes critical here.

  The system may need:

  - wall-clock UTC for forensic correlation;
  - monotonic time for deadlines;
  - broker/server time as remote evidence;
  - event-origin time as domain data;
  - device boot-relative time for diagnostics.

  These clocks cannot safely substitute for one another.

  For example, shutdown timeout must use monotonic time. If it uses UTC and NTP moves the clock backward, a five-second timeout might last much longer.

  Future-time validation uses UTC because it compares timestamps across systems. Its result should acknowledge clock uncertainty.

  ## Evidence-based environment classification

  src/shakealert_lab/classifier.py demonstrates another validation principle:

  > Conflicting trust evidence produces UNKNOWN, not a guessed answer.

  If independent sources claim:

  endpoint → SCENARIO
  payload  → LIVE

  the result is:

  UNKNOWN

  It does not pick whichever source was checked first.

  That is a conflict quarantine pattern. Contradictory evidence reduces authority.

  This matters because environment classification may control whether a message can reach operational systems.

  ## Message failure versus systemic failure

  Your Lesson 7 thresholds—consecutive failures and error-rate windows—are useful supervisory signals, but some violations should immediately become systemic.

  Examples:

  - one malformed payload: preserve, reject, continue;
  - one unknown optional field: perhaps accept with recorded limitation;
  - verified destination conflicts with claimed environment: quarantine immediately;
  - payload exceeds hard memory ceiling: disconnect;
  - capture storage fails: disconnect;
  - validator itself throws unexpectedly: system failure;
  - validation configuration changes unexpectedly: system failure;
  - repeated schema failures: supervisor may latch FAILED.

  The message validator reports facts. The supervisor decides lifecycle policy.

  Validator
     │ reports issue
     ▼
  Supervisor
     │ applies escalation policy
     ▼
  continue / quarantine / disconnect / FAILED

  ## Design patterns demonstrated

  - Chain of Responsibility: distinct validation layers apply independent policies.
  - Specification Pattern: admissibility rules are explicit and composable.
  - Result Object: expected invalid input produces structured evidence rather than an exception.
  - Fail-Closed Gate: downstream authority exists only after successful validation.
  - Conflict Quarantine: contradictory evidence becomes UNKNOWN.
  - Policy/Mechanism Separation: validation machinery does not invent operational limits.

  Exceptions should generally represent failures of the validation mechanism itself. A malformed message is an expected input outcome and belongs in ValidationResult.

  ## Cross-industry equivalents

   Domain             Equivalent
  ━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   Automotive         AUTOSAR end-to-end protection checks sequence counters, CRCs, freshness, and data IDs before signals are trusted
  ─────────────────  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Aerospace          Command-validation chains check source, format, range, vehicle mode, inhibit state, and command legality
  ─────────────────  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Industrial PLCs    Input conditioning and plausibility logic validate ranges, quality flags, stale data, and redundant-sensor agreement
  ─────────────────  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Robotics           Sensor pipelines reject impossible timestamps, frame IDs, calibration mismatches, and kinematic outliers
  ─────────────────  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Linux drivers      Drivers validate descriptor lengths, DMA bounds, device status, and packet structure before exposing data upward
  ─────────────────  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   RTOS firmware      Communication tasks validate framing, CRC, sequence, freshness, and authorization before posting commands to control tasks

  The universal architecture is:

  received ≠ trusted
  parsed ≠ valid
  valid ≠ authorized

  ## Current repository limitations

  The foundation is correct, but the validator is intentionally minimal.

  A higher-assurance implementation would need:

  - explicit validation layers;
  - stable issue severity;
  - protocol-specific schema rules outside the generic validator;
  - policy provenance and versioning;
  - validation-result persistence;
  - bounded diagnostic details;
  - clock-quality evidence;
  - rate-based escalation;
  - rules for conflicting metadata;
  - deterministic handling of validator defects;
  - resource budgets for hostile inputs.

  Most importantly, a validation result should eventually record the policy version that produced it. Otherwise replaying the same capture after a software update can produce a
  different result without explaining why.

  ## Architecture review questions

  1. A message is structurally valid and authentic, but its destination metadata says Scenario while its payload claims Production. Which module quarantines it, and should the
     receiver remain connected?

  2. Should validation stop at the first failed rule or evaluate every safe rule and return all issues? Consider timing determinism, diagnostic value, and hostile inputs.
  3. The local UTC clock is later discovered to have been wrong by 30 seconds. How should previously rejected future-dated captures be reconsidered without modifying their original
     evidence?

  4. Which validation policies may be changed live, and which should require a new receiver activation generation?
  5. A validator crashes on one preserved payload because of an internal software defect. Is that a message failure or a systemic failure? Define the supervisor transition and
     replay strategy.
