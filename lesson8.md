## Lesson 8: Native capture as the system of record

  The major module is src/shakealert_lab/storage/capture.py.

  Its governing rule is:

  > Preserve what arrived before deciding what it means.

  The pipeline is conceptually:

  Transport
     │
     ▼
  MessageEnvelope
     │
     ▼
  NativeCapture ──► durable RawMessageStore
                           │
                           ▼
                   validation / parsing

  ### Why this module exists

  Transport reception and message interpretation belong to different trust domains.

  When bytes arrive, the system may not yet know whether they are:

  - valid XML;
  - complete;
  - correctly routed;
  - duplicated;
  - malformed;
  - malicious;
  - from the expected environment;
  - interpretable by the current software version.

  If parsing happens before preservation, the parser becomes an accidental evidence filter. Anything it cannot understand may disappear.

  NativeCapture creates a stable evidence record containing:

  - the original payload bytes;
  - receipt time;
  - connection and protocol identity;
  - destination;
  - verified transport metadata;
  - message identifiers;
  - delivery information;
  - payload size and digest;
  - a unique capture identity.

  Its job is preservation, not interpretation.

  ### What problem it solves

  Imagine a newly deployed parser rejects an unfamiliar USGS field.

  Without native capture:

  message → parser failure → evidence lost

  With native capture:

  message → durable capture → parser failure
                                │
                                ▼
                       software can be corrected
                       and evidence replayed

  This turns parser defects from irreversible data loss into recoverable software defects.

  It also lets engineers answer different questions independently:

  - What bytes actually arrived?
  - When did this host receive them?
  - Through which connection?
  - What metadata did the transport verify?
  - What did the parser conclude at that time?
  - Would a newer parser reach a different conclusion?

  ### What happens if it is removed

  The receiver becomes dependent on its current interpretation logic.

  Consequences include:

  - malformed messages disappear;
  - parser upgrades cannot be tested against exact historical input;
  - incident investigations depend on reconstructed logs;
  - normalization defects become irreversible;
  - duplicate and ordering analysis loses its original evidence;
  - a future schema cannot reinterpret earlier messages;
  - engineers may confuse “parser rejected it” with “broker never delivered it.”

  In a warning system, that distinction is fundamental.

  ### The embedded principle

  This demonstrates capture first, interpret second.

  The embedded equivalent is preserving raw sensor samples before applying calibration, filtering, or feature extraction.

  For example:

  ADC sample → immutable acquisition buffer → calibration → filtering → decision

  If only the filtered result is retained, engineers cannot determine whether a fault originated in:

  - the physical sensor;
  - analog acquisition;
  - timing;
  - calibration;
  - filtering;
  - threshold logic.

  Raw evidence separates acquisition truth from interpretation.

  ### The design patterns

  #### 1. Event sourcing

  The native record is the authoritative historical fact. Derived representations can be rebuilt from it.

  The project does not treat normalized output as the original truth.

  #### 2. Write-ahead evidence

  The system records the input before performing fallible downstream work. This resembles a write-ahead log, although the capture store records inbound evidence rather than database
  mutations.

  #### 3. Immutable value object

  NativeCapture binds a unique capture identity to a MessageEnvelope. Downstream modules should create new results rather than mutate the original evidence.

  #### 4. Anti-corruption boundary

  Protocol-native input is isolated from the internal interpretation model. Broker details cannot silently redefine what the application believes the event means.

  #### 5. Provenance chain

  Derived results can point back to a specific capture ID. This permits traceability:

  normalized record
        │
        ▼
  validation result
        │
        ▼
  capture ID
        │
        ▼
  original bytes

  ## Why the payload is encoded rather than decoded as text

  The repository stores the payload losslessly using base64.

  This is an engineering decision, not merely a serialization choice.

  A broker payload may contain:

  - arbitrary bytes;
  - an unexpected character encoding;
  - invalid UTF-8;
  - embedded nulls;
  - compressed content;
  - a future binary format.

  Converting it immediately into text would assert meaning that has not yet been verified. Base64 lets JSON carry the exact bytes without pretending those bytes are valid text.

  The round-trip test deliberately includes 00 and FF, demonstrating that byte identity—not textual readability—is the contract.

  ## Atomic persistence

  RawMessageStore.save() does not write directly to the final filename.

  Its storage transaction is:

  create unique temporary file
          │
          ▼
  write complete record
          │
          ▼
  flush userspace buffers
          │
          ▼
  fsync file
          │
          ▼
  link to final unique name
          │
          ▼
  fsync directory
          │
          ▼
  remove temporary name

  This protects against exposing a partially written final record.

  A reader should see either:

  - no final capture; or
  - one complete final capture.

  It should not see half a JSON document after a crash.

  ### Why exclusive creation matters

  The temporary file uses exclusive creation and refuses symlink traversal. The final link also refuses overwriting an existing capture.

  The rule is:

  > A capture identity may be committed once, never replaced.

  If a filename already exists, the system fails detectably. It does not decide that the new record is “probably the same.”

  That is exactly your earlier principle: prefer explicit detectable failure over silent data loss.

  ## Durability versus atomicity

  These concepts are related but different.

  - Atomicity: readers do not observe a partial final capture.
  - Durability: after success is reported, the capture is intended to survive a crash.

  Flushing the file protects its contents. Flushing the directory protects the directory entry that names it.

  A professional firmware architect asks both:

  1. Can another task observe a torn record?
  2. Can acknowledged evidence vanish after power loss?

  Passing the first does not automatically pass the second.

  ## Integrity checking

  The capture stores:

  - the declared byte count;
  - a SHA-256 payload digest.

  When loaded, the repository reconstructs the envelope and verifies both.

  This detects accidental corruption or tampering, but it does not prove authenticity.

  A digest stored beside the payload can answer:

  > Has this record changed relative to its stored digest?

  It cannot independently answer:

  > Did USGS create this message?

  Authenticity would require trusted transport evidence, a digital signature, or a protected external integrity root.

  That distinction prevents a common architecture mistake: treating hashing as authentication.

  ## Failure ownership

  Storage owns whether evidence was durably committed.

  The parser must never convert a storage failure into “invalid message.” Those are entirely different failures:

  invalid payload
      = evidence was preserved, interpretation rejected

  storage failure
      = evidence may not have been preserved

  A storage failure should normally become a system-level failure:

  RUNNING → FAILED → stop accepting/disconnect

  Continuing reception after capture storage fails would create silent evidence loss.

  ## Cross-industry equivalents

   Domain             Equivalent pattern
  ━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   Automotive         Freeze-frame data and event data recorder snapshots captured before diagnostic interpretation
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Aerospace          Flight-data recording and raw telemetry retention before ground-processing transformations
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Industrial PLCs    Historian samples and sequence-of-events records retained independently of HMI interpretation
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Robotics           Sensor bags containing raw camera, LiDAR, IMU, and timestamp streams for deterministic replay
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Linux drivers      DMA/ring-buffer data preserved before higher layers decode packets or input events
  ─────────────────  ─────────────────────────────────────────────────────────────────────────────────────────────────────────────
   RTOS firmware      Acquisition task writes immutable records to a bounded buffer or journal before analysis tasks consume them

  The same separation appears everywhere:

  acquisition truth ≠ interpreted meaning

  ## Professional review of this repository

  The current module establishes the right foundation:

  - native bytes survive serialization;
  - records receive unique identities;
  - final files are never overwritten;
  - file and directory persistence are explicitly flushed;
  - basic stored-record integrity is checked;
  - validation occurs after preservation.

  A production-grade evolution would still need explicit policies for:

  - disk-capacity reservation;
  - filesystem-full behavior;
  - storage latency deadlines;
  - boot-time recovery of abandoned temporary files;
  - retention and archival;
  - media wear;
  - capture-directory ownership;
  - concurrent writers;
  - stronger tamper evidence;
  - whether receipt may be acknowledged before durable commit.

  The hardest architectural question is acknowledgement ordering:

  receive
    ↓
  durably preserve
    ↓
  acknowledge

  If acknowledgement happens first, a crash can lose an already-acknowledged message.

  If durable capture happens first, broker delivery may be delayed, and redelivery can create duplicates. Duplicates are usually recoverable; acknowledged-but-lost evidence is not.

  ## Architecture review questions

  1. The filesystem reports a successful capture, but the broker connection fails before acknowledgement. The message is later redelivered. Should the capture store create a second
     record, reuse the first, or record a relationship between them? Who owns that decision?

  2. The disk becomes full while a message is being committed. Define the lifecycle transition and the exact order for closing callback acceptance, stopping transport input, and
     preserving diagnostic evidence.

  3. Should the capture filename be based on a random capture ID, the broker message ID, or the payload digest? Compare collision behavior, duplicate detection, privacy, and trust
     assumptions.

  4. An RTOS target has no filesystem and only limited flash with finite erase cycles. Design an equivalent crash-consistent native-capture journal. What makes a record committed
     after sudden power loss?

  5. SHA-256 detects changed content only when the stored digest remains trustworthy. What additional mechanism would you introduce if the capture directory itself might be modified
     by an attacker?
