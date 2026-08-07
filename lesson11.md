# Lesson 11: Provenance-preserving normalization

The central module is `src/shakealert_lab/normalization.py`, supported by `src/shakealert_lab/models/event.py`.

Its governing rule is:

> Normalize only what has been verified, and always retain a path back to the original evidence.

```
NativeCapture
     │
     ▼
validated interpretation
     │
     ▼
verified fields
     │
     ▼
NormalizedMessage
     │
     └── capture_id ──► original bytes
```

## Why normalization exists

External protocols organize information for transport compatibility—not necessarily for application clarity.

One protocol might represent magnitude as:

```xml
<mag units="Mw">4.7</mag>
```

Another might use a field conceptually equivalent to:

```python
magnitude_value = 4.7
magnitude_scale = "Mw"
```

The rest of the application should not need to understand every wire representation.

Normalization creates a protocol-neutral representation that downstream modules can consume without importing:

- ActiveMQ classes
- XML parser details
- broker headers
- protocol-specific naming
- version-specific wire structures

It is an architectural firewall between external representation and internal meaning.

## What happens if normalization is removed

Protocol assumptions spread through the system:

```
dashboard parses XML
storage knows broker headers
alert logic knows topic naming
metrics know protocol versions
replay knows ActiveMQ objects
```

This creates protocol contamination.

A change in one external schema then forces modifications throughout the application. Testing becomes harder because every module needs realistic protocol objects.

Without normalization, the external supplier effectively controls the internal architecture.

## What normalization must not do

Normalization must not invent missing knowledge.

The repository deliberately refuses to fabricate:

- event identity
- countdown values
- intensity
- PGA or PGV
- site-specific values
- message type
- units

If a verified parser supplies no fields, the normalized mapping remains empty.

That is better than filling gaps with plausible defaults:

```
magnitude missing → 0.0
```

A default like `0.0` destroys information. It makes these states indistinguishable:

- magnitude was genuinely zero
- field was absent
- parsing failed
- field was not supported
- field was rejected
- value was redacted
- value was unknown

A world-class firmware architect preserves epistemic state: what the system knows, does not know, and cannot trust.

## The normalized record

The repository's `NormalizedMessage` carries:

- source `capture_id`
- UTC receipt time
- evidence-based environment
- message disposition
- optional verified message type
- immutable verified fields

Each member serves a separate purpose:

| Member | Architectural purpose |
|---|---|
| `capture_id` | Trace back to original evidence |
| `received_utc` | Correlate processing with external events |
| `environment` | Preserve the classified safety domain |
| `disposition` | Record relationship to prior messages |
| `message_type` | Identify verified semantic category |
| `fields` | Carry only accepted interpreted values |

The normalized record is derivative evidence. It never replaces `NativeCapture`.

## The anti-corruption layer pattern

Normalization is an anti-corruption layer.

```
External protocol model
         │
         ▼
 adapter + verifier + normalizer
         │
         ▼
 Internal canonical model
```

"Corruption" here does not mean malicious data. It means allowing another system's terminology and assumptions to distort your internal design.

For example, suppose a vendor calls something `eventTime`, but documentation reveals it is actually the time its server processed the event.

Copying it internally as `event_origin_time` would create false meaning.

A professional normalizer asks:

- What is the documented semantic definition?
- Which clock produced it?
- What units apply?
- Is it measured, calculated, or inferred?
- Is it optional?
- What establishes its validity?
- Which protocol version defines it?

Field names are not sufficient evidence.

## Verified fields

`src/shakealert_lab/models/event.py` provides `VerifiedFieldSet`:

```
VerifiedFieldSet
├── schema_identifier
└── immutable values
```

The schema identifier is critical because a field without a schema has unstable meaning.

Consider:

```
"intensity": 5
```

That value is ambiguous:

- Modified Mercalli Intensity?
- Japanese Meteorological Agency scale?
- integer category?
- predicted intensity?
- observed intensity?
- site-specific estimate?
- raw vendor enumeration?

A schema binds names to documented semantics.

The safest principle is:

> A value without identity, unit, provenance, and semantic definition is not yet domain data.

## Normalization is intentionally lossy

Native capture preserves everything received. Normalization selects only what the application understands and trusts.

Therefore:

```
NativeCapture ──► NormalizedMessage
```

must not be expected to reverse perfectly:

```
NormalizedMessage ──X──► original payload
```

That is why native evidence remains authoritative.

Loss is acceptable when it is:

- deliberate
- documented
- versioned
- traceable
- reproducible
- never mistaken for the original record

## Immutability

Both `NormalizedMessage` and `VerifiedFieldSet` expose immutable mappings.

This demonstrates the immutable snapshot pattern.

A consumer receives one coherent interpretation:

```
Normalization result V1
├── environment = SCENARIO
├── disposition = NEW
└── verified fields = snapshot
```

Another task cannot modify that shared interpretation while it is being processed.

In embedded systems, immutability reduces:

- locking
- aliasing
- race conditions
- partial updates
- hidden ownership
- inconsistent audit output

The tradeoff is that corrections create new records instead of editing old ones. That is desirable for traceability.

## Missing, null, invalid, and unknown

The current scalar model permits `None`, but higher assurance designs often require more explicit states.

These are semantically different:

| State | Meaning |
|---|---|
| Missing | Source did not provide the field |
| Null | Source explicitly provided no value |
| Invalid | Source provided a value that violated policy |
| Unknown | System cannot determine the value |
| Unsupported | Current software does not understand the field |
| Redacted | Value exists but may not be disclosed |
| Not applicable | Field has no meaning for this message type |

Collapsing all of these into `None` simplifies consumers but loses engineering information.

A stronger model might use a field record:

```
VerifiedField
├── name
├── value
├── unit
├── status
├── source path
├── verification rule
└── schema version
```

That costs memory and complexity. Whether it is justified depends on the safety and audit requirements.

## Provenance depth

The repository currently provides record-level provenance through `capture_id`.

That answers:

> Which native capture produced this normalized record?

It does not yet answer:

> Which exact source element produced this particular field?

Field-level provenance might record:

```
magnitude.value
    ├── capture_id
    ├── source path
    ├── parser version
    ├── schema version
    ├── unit conversion
    └── validation rule version
```

This becomes important when values undergo:

- unit conversion
- calibration
- rounding
- coordinate transformation
- fusion with other sources
- clock correction
- quality filtering

The more consequential the transformation, the stronger the provenance required.

## Unit handling

A dangerous normalizer returns a naked numeric value:

```
distance = 12
```

The consumer cannot determine whether that means:

- meters
- kilometers
- miles
- sensor counts

Professional architectures make units part of the type or field contract.

The transformation should also record whether it was exact:

```
12 miles → 19.312128 kilometers
```

Later rounding to `19.3` is another transformation with another policy.

Automotive and aerospace failures have occurred because numerically valid values crossed interfaces with incompatible units. Range checks alone do not catch that.

## Normalization policy must be versioned

Two versions of a normalizer might interpret the same capture differently:

```
Capture C42
├── normalizer v1 → field omitted
└── normalizer v2 → field verified and populated
```

Neither result should overwrite the other.

A production-grade normalized record should identify:

- capture ID
- parser implementation/version
- source schema/version
- validation policy/version
- normalization policy/version
- creation time
- any conversions performed

Then offline replay can produce a new interpretation without rewriting history.

## Parsing authority versus normalization authority

A parser establishes structure:

> "this XML element contains text 4.7"

A normalizer establishes canonical representation:

> "this verified field represents magnitude value 4.7 under schema X"

Neither should independently grant operational authority.

That remains a later policy decision:

```
Parsed
   ↓
Validated
   ↓
Normalized
   ↓
Policy-authorized
   ↓
Effect
```

The essential rule is:

> normalized ≠ authorized

A perfectly normalized Production message received on a Scenario-only system must still have no operational effect.

## Cross-industry equivalents

| Domain | Equivalent |
|---|---|
| Automotive | AUTOSAR signal mapping converts bus-specific frames into typed application signals with scaling and validity |
| Aerospace | Telemetry decommutation transforms raw frames into engineering units while retaining packet and calibration provenance |
| Industrial PLCs | I/O mapping converts raw register values into tagged engineering values with quality status |
| Robotics | Driver nodes convert device-native packets into canonical sensor messages and coordinate frames |
| Linux drivers | Device-specific register and descriptor formats become stable kernel subsystem objects |
| RTOS firmware | Hardware acquisition tasks convert ADC counts or bus frames into immutable typed samples for control tasks |

The shared pattern is:

```
device representation
       ↓
verified transformation
       ↓
stable internal contract
```

## Linux driver equivalent

A network device driver understands hardware descriptors, DMA buffers, and device status bits. Upper networking layers should receive a standard packet buffer—not vendor-specific descriptor layouts.

If vendor details leak upward:

- every network layer depends on that NIC
- replacing hardware becomes expensive
- hardware quirks spread
- tests require physical hardware assumptions

Normalization serves the same purpose here: isolate external representation at the boundary.

## RTOS equivalent

Consider an ADC acquisition task:

```
ADC counts
   │
   ▼
calibration validation
   │
   ▼
engineering-unit sample
├── value
├── unit
├── timestamp
├── quality
├── channel identity
└── raw-sample reference
```

The control task consumes the verified engineering-unit sample. Diagnostic tooling can still trace it to raw counts.

This is nearly identical to:

```
NativeCapture → NormalizedMessage
```

## Failure ownership

A malformed source value is a message-level failure.

A broken normalizer is a systemic failure.

Examples:

- required field absent: message rejected or partially normalized according to schema
- field outside verified range: field/message invalid
- unsupported optional field: preserve and record unsupported status
- unit conversion overflows: message rejected
- normalizer throws unexpectedly: trust mechanism failed
- unknown schema selected automatically: systemic configuration failure
- normalized output cannot retain provenance: reject the transformation

The normalizer may report uncertainty. It may not hide it.

## Current repository strengths

The module demonstrates several sound decisions:

- normalization is protocol-neutral
- native provenance is retained
- fields are immutable snapshots
- missing information is not fabricated
- environment and disposition remain explicit
- the schema-aware field container is separate from transport code

## Current repository limitations

A production-grade design would still need:

- field-level provenance
- explicit units
- explicit missing/invalid/unknown states
- parser and normalizer version identifiers
- schema compatibility rules
- bounded field names and values
- canonical numeric rules
- timezone guarantees
- conversion audit records
- deterministic serialization
- persistent normalized results
- validation that `message_type` matches its schema
- prevention of untrusted field names becoming log or database keys

The current immutable mapping is shallowly immutable: consumers cannot replace entries through the exposed mapping, but the architecture still relies on allowed values being immutable scalars.

## Design patterns demonstrated

- **Anti-Corruption Layer**: external protocol assumptions stop at the boundary.
- **Canonical Data Model**: downstream modules share one internal representation.
- **Immutable Snapshot**: consumers receive stable interpreted state.
- **Provenance Chain**: derived data points back to native evidence.
- **Materialized View**: normalized records can be regenerated from the source record.
- **No-Fabrication Rule**: absence remains absence.
- **Policy/Mechanism Separation**: normalization represents meaning but does not authorize effects.

## Architecture review questions

1. A source field reports distance in miles, while the canonical model uses kilometers. What provenance must be recorded so another engineer can reproduce the exact normalized value?

2. A newer schema adds an optional field that the current normalizer does not understand. Should the entire message fail, should the field be omitted, or should it carry an `UNSUPPORTED` status?

3. Two independent source fields disagree about event magnitude. Should normalization choose one, expose both, or refuse to produce a canonical magnitude? Who owns the precedence policy?

4. A normalizer defect is discovered after 100,000 records have been generated. Design the correction process without modifying either native captures or historical normalized records.

5. Which normalized fields would you allow a control task to consume directly, and what additional authorization object or quality contract should be required before any operational effect?
