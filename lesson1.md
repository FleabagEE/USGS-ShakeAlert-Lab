## Lesson 1: safety.py — the fail-closed interlock

The first architectural lesson is that professional embedded systems begin by controlling what the system is allowed to do—not by processing data.

The module is `src/shakealert_lab/safety.py`.

### Why it exists

This laboratory handles earthquake-warning information and could eventually interact with operational systems. An accidental output, connection, or activation could have consequences far beyond a normal software defect.

The module therefore establishes a process-level invariant:

> Unless passive laboratory mode is explicitly declared, the application must not start.

This is stronger than having a configuration option that defaults to “safe.” It demands affirmative evidence of the safe state.

### What problem it solves

It protects against several common failure modes:

- Missing configuration
- Misspelled configuration
- Unexpected deployment environments
- Operators assuming defaults
- New entry points accidentally starting active behavior
- Future code being added without understanding the laboratory boundary

Only the exact value `false` is accepted for `ALLOW_OPERATIONAL_OUTPUTS`.

Values such as `False`, `0`, an empty string, or a missing variable are rejected. This deliberately avoids “helpful” interpretation. In safety-sensitive systems, flexible parsing creates ambiguous states.

### What would happen if it were removed

Removing this module would not immediately make the system publish messages. That is precisely why removing it might appear harmless.

The real damage would be architectural:

- Safety would depend on every individual transport and command behaving correctly.
- A future module could introduce an operational path without a common gate.
- Missing deployment configuration could silently become permission.
- Reviewers could no longer identify one mandatory startup invariant.
- Tests could prove individual components safe but not prove the application safe as a whole.

A firmware architect thinks about the next five years of changes, not merely what today’s code happens to do.

### Embedded principle: fail-safe defaults

The demonstrated principle is:

> Loss of control information must move the system toward the safe state.

If the environment variable disappears, startup fails. If its value is corrupted, startup fails. If an operator misunderstands the permitted value, startup fails.

This resembles an energized-to-run circuit: positive, valid control evidence is required to leave the safe state.

The repository reinforces this through a second independent decision in configuration: `connect_authorized=false`. These controls answer different questions:

- `ALLOW_OPERATIONAL_OUTPUTS=false`: Is this process constrained to laboratory behavior?
- `connect_authorized`: Is this particular external connection explicitly authorized?

That is defense in depth, not duplication.

### Design pattern: guard clause plus safety interlock

The immediate software pattern is a guard clause: reject unsafe state before useful work begins.

The architectural pattern is a safety interlock:

```
Process starts
       │
       ▼
Safety evidence exact and present?
       │
    no ├────────► terminate with an explicit failure
       │
   yes ▼
Application initialization may continue
```

It also acts as a policy-enforcement point. Instead of spreading one safety rule across every module, the repository gives the rule a name, exception type, test suite, and mandatory position in startup.

### Why dependency injection matters here

The function can inspect either the real process environment or a supplied environment mapping.

The important decision is not programming convenience. It makes the safety rule deterministically testable without mutating the host:

- Test the accepted state.
- Test a missing value.
- Test every malformed value.
- Demonstrate that no permissive fallback exists.

Safety requirements that cannot be tested independently tend to decay into documentation.

### Where this pattern appears

**Automotive:**
- Torque-producing software remains inhibited until ignition state, watchdog health, sensor plausibility, and communication status are valid.
- Missing CAN data should not be interpreted as permission to actuate.

**Aerospace:**
- Flight-control modes require explicit validity and engagement conditions.
- Invalid or stale mode evidence causes reversion to a defined degraded state rather than an inferred active state.

**Industrial PLCs:**
- A machine run command is combined with guard switches, emergency-stop state, and safety-relay feedback.
- Loss of a permissive signal prevents motion.

**Robotics:**
- Motor drivers remain disabled until control mode, localization health, limits, and emergency-stop state are confirmed.
- A crashed planning process must not leave motion implicitly enabled.

**Linux kernel drivers:**
- Hardware is not exposed as operational until resources, device identity, DMA boundaries, interrupts, and initialization have succeeded.
- Probe failure unwinds initialization rather than leaving a partially active device.

**RTOS firmware:**
- Actuator tasks wait for system initialization, calibration, communication health, and safety-state events.
- A missing event bit is treated as “not ready,” never as “probably ready.”

The domain changes; the invariant does not:

> Permission to perform hazardous work must be explicit, narrow, testable, and revocable.

### Architectural weaknesses to notice

A world-class architect should also see what this module cannot guarantee:

- It works only if every entry point calls it.
- It is a software interlock, not an independent hardware safety mechanism.
- An environment variable expresses deployment intent but does not prove authorization.
- The name is negatively phrased: safety requires `ALLOW_OPERATIONAL_OUTPUTS=false`, which increases cognitive load.
- It prevents startup but does not continuously supervise runtime state.
- It cannot stop another process from bypassing this repository.

The repository compensates partially with startup integration, shell preflight checks, configuration authorization, inert services, and tests. But the Python function alone is not a complete safety case.

## Your design questions

Before we continue, answer these as the system architect:

1. Why should a missing safety variable cause termination instead of selecting passive mode automatically?
2. Suppose a developer adds a new executable that imports receiver components directly and never calls the CLI. How would you make bypassing the interlock structurally difficult?
3. Should `ALLOW_OPERATIONAL_OUTPUTS` ever permit operational output in this repository, or should operational behavior require a separate executable and deployment artifact?
4. If the process passes the interlock and the environment changes later, should the system continue running? What additional runtime supervision would you design?