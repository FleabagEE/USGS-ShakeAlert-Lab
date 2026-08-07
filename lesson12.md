Lesson 12: Deterministic replay as a safety boundary

The central module is src/shakealert_lab/replay/engine.py, constrained by docs/replay-procedure.md.

Its governing rule is:

Historical evidence may be re-executed through analytical software, but replay must never be mistaken for new live authority.

Replay is not merely "read files and send them again." It recreates selected aspects of execution while controlling:

input ordering
timing
filtering
pause and step behavior
destination
side effects
replay identity
Native captures
      │
      ▼
Replay Engine
├── ordering
├── timing scale
├── filtering
├── pause/step/stop
└── replay context
      │
      ▼
Internal queue only
      │
      ▼
Analysis / validation / testing
Why replay exists

Native captures preserve what arrived. Replay makes that evidence executable.

It supports:

reproducing parser defects
testing a new validator
rebuilding sequence state
comparing normalizer versions
validating recovery behavior
reproducing message bursts
testing queue saturation
investigating incidents
regression-testing fixes
training operators without live effects

Without replay, historical evidence is passive. Engineers can inspect it, but they cannot reliably reproduce how the system behaved.

What happens if replay is removed

Several important guarantees become difficult to demonstrate:

parser fixes cannot be tested against exact historical bytes
normalization upgrades cannot be compared reproducibly
sequence recovery depends on hand-built test data
concurrency failures become difficult to reproduce
timing-sensitive defects remain anecdotal
incident analysis relies on operator memory
test fixtures drift away from real protocol behavior

A world-class architecture treats captured evidence as a permanent regression corpus.

The repository's replay architecture

The module contains four important concepts:

ReplayOptions
ReplayController
ReplaySink
replay()
Replay options

The current option is speed.

speed = 1.0  → original inter-message timing
speed = 2.0  → twice as fast
speed = 0.5  → half speed

The engine preserves relative receipt-time gaps rather than waiting until historical absolute timestamps.

If the captures were received at:

T+0 seconds
T+4 seconds
T+10 seconds

then replay at 2× produces delays of:

2 seconds
3 seconds

Absolute historical time is not reused as the execution clock.

Replay controller

The controller provides:

pause
resume
single step
stop

This is a supervisory-control boundary.

A paused replay retains its position. A step grants permission for one capture to proceed. Stop prevents future sends.

The important abstraction is not the user interface. It is controlled advancement through recorded evidence.

Replay sink

The engine sends captures through a ReplaySink contract.

The repository provides only InternalQueueSink, which places captures into an in-process queue.

This is a deliberate safety restriction:

Replay Engine ──► Internal queue
             ──X──► USGS broker
             ──X──► Production
             ──X──► CUBE/PX-01
             ──X──► physical output

The current replay system has no external publisher.

Replay function

The engine:

selects captures using an optional predicate;
maintains their supplied order;
waits for controller permission;
calculates relative delay;
applies speed scaling;
sends each selected capture;
reports how many were sent.

This is a small implementation, but it exposes several deep architectural decisions.

Replay must be observationally isolated

The largest replay hazard is not corrupted input. It is valid historical input reaching a live side-effect path.

Suppose a preserved Production message is replayed internally. It may still contain:

Production environment metadata
a valid event identifier
a recent-looking origin time
an authentic payload
a destination name associated with operational processing

If downstream code checks only the embedded message environment, it could mistake replayed Production evidence for a new Production delivery.

Therefore, replay needs an independent execution identity:

ExecutionContext
├── mode = REPLAY
├── replay_session_id
├── source_capture_id
├── started_by
├── authorized purpose
├── permitted sinks
└── side_effects_allowed = false

The replay context must not overwrite the original capture's environment. Both facts matter:

Original message environment = PRODUCTION
Current execution mode        = REPLAY

Changing the original environment to SCENARIO would falsify evidence. Ignoring replay mode would be unsafe.

Data identity versus execution identity

This distinction is fundamental:

NativeCapture: "What was originally received?"

ReplayContext: "Why and how is it being processed now?"

The same capture might be processed in:

original live reception
offline investigation
regression testing
schema migration
operator training
sequence-state reconstruction

Its original identity remains constant. Its execution identity changes.

Automotive and aerospace systems frequently express this through operating mode, test mode, simulation flags, or partition identity.

Determinism

Replay is useful only if engineers can explain repeated outcomes.

Strict determinism requires control over more than message order.

Potential nondeterministic inputs include:

wall-clock time
monotonic time
random jitter
thread scheduling
queue capacity
filesystem state
network availability
environment variables
configuration version
parser version
policy version
external database contents
iteration order
floating-point environment

A deterministic replay session should bind:

ReplayManifest
├── ordered capture IDs
├── capture digests
├── replay-engine version
├── parser/validator versions
├── policy versions
├── configuration digest
├── speed/timing policy
├── clock model
├── random seed
├── filtering rule
├── sink identity
└── expected side-effect policy

Running the same manifest should yield explainable results.

"Same messages" alone does not mean "same experiment."

Three replay time models

A professional replay system should explicitly choose a time model.

1. Wall-clock replay

The software sees the current real clock.

Useful for integration testing, but historical freshness checks may fail because old messages appear stale.

2. Scaled historical clock

The software sees simulated time derived from recorded timestamps.

Useful for reproducing timeout, freshness, and state-machine behavior.

simulated_time =
    replay_start +
    historical_elapsed / speed
3. Step-driven logical clock

Time advances only when the replay controller advances.

Useful for deterministic firmware and state-machine testing.

step 1 → time T0
step 2 → time T1
step 3 → time T2

The current repository scales sleep delays, but it does not inject a simulated clock into downstream components. Therefore, it reproduces pacing—not complete temporal semantics.

Timing provenance

The repository uses received_at_utc to calculate spacing.

That is one reasonable choice, but other timestamps could produce different behavior:

broker timestamp
producer timestamp
event-origin time
local monotonic arrival time
capture commit time

Replay must state which timeline it reproduces.

For receiver stress testing, local arrival spacing is usually appropriate.

For domain evolution, event timestamps might be more relevant.

For timeout reproduction, monotonic capture-time intervals are preferable because UTC may jump due to clock correction.

Filtering semantics

The engine filters captures before calculating delays.

Suppose the original stream is:

A at T+0
B at T+2
C at T+10

If B is filtered out, the selected stream becomes:

A at T+0
C at T+10

The replay waits the full ten-second historical gap between A and C.

That preserves elapsed time across omitted records.

An alternative policy could compress removed intervals:

A → C immediately or after a synthetic delay

Neither policy is universally correct. The replay manifest must say which one applies.

Filtering can materially change system behavior:

heartbeat removal may trigger a timeout
event removal may change sequence classification
cancellation removal may leave an event active
duplicate removal may hide idempotency defects

A filtered replay is a new experiment, not a faithful reproduction of the original stream.

Ordering contract

The current engine preserves list order. It does not establish that the input is correctly ordered.

Therefore, ownership must be explicit:

Capture loader: produces a verified ordered sequence

Replay engine: preserves supplied sequence

Possible order keys include:

capture commit order
receipt timestamp
broker delivery sequence
event sequence
capture ID

These are not interchangeable.

Sorting globally by event sequence would incorrectly compare unrelated events. Sorting only by UTC may be ambiguous when timestamps tie or clocks move.

A robust ordering key might include:

connection activation
delivery sequence
receipt monotonic counter
capture ID as deterministic tie-breaker

Only verified protocol semantics can establish the authoritative choice.

Backpressure during replay

InternalQueueSink uses a nonblocking queue insertion.

If the queue is bounded and full, insertion fails. That is desirable because replay should not silently discard evidence.

The replay supervisor must then decide whether to:

pause and wait for capacity
fail the replay
stop immediately
record a deterministic saturation point

Automatically retrying changes timing. Blocking indefinitely destroys bounded execution. Dropping changes evidence.

For deterministic testing, failing at an exact capture ID is often best:

REPLAY_FAILED
capture_id = C42
reason = SINK_SATURATED
sent_count = 41

Then the same manifest and capacity should reproduce the same failure.

Pause and stop responsiveness

The current controller checks permission before calculating each delay.

Once the sleeper begins a long delay, stop does not interrupt that sleep.

Example:

next historical gap = 30 minutes
speed = 1
stop requested after 1 second

The current replay may remain asleep for the rest of the delay.

A production controller should use interruptible deadline waiting:

wait until:
    deadline reached
    OR stop requested
    OR pause requested

In an RTOS, this could be a timed queue receive, event group, task notification, or timer combined with a cancellation event.

Responsiveness itself needs a requirement:

stop-to-quiescence ≤ 100 ms

Without a bound, "supports stop" is incomplete.

Replay lifecycle

Replay needs its own explicit state machine:

CREATED
   │ validated manifest
   ▼
READY
   │ start
   ▼
RUNNING ◄──── resume
   │  │
pause │
   ▼  │
PAUSED
   │
stop/failure/completion
   ▼
STOPPING
   │ sink quiesced + evidence committed
   ▼
STOPPED / COMPLETED / FAILED

One owner should control transitions. Worker or sink callbacks report facts but do not independently choose final lifecycle state.

This mirrors your Lesson 6 receiver lifecycle.

Replay and acknowledgements

Internal replay has no broker acknowledgement.

That is a strength: it cannot accidentally alter broker delivery state.

If a future replay adapter sends to a broker—even a local broker—the design must not reuse live credentials or destination configuration.

A safe external replay boundary would require:

loopback-only address enforcement
separate executable
separate credentials or no credentials
explicit replay topic namespace
no route to Production
no route to CUBE
startup interlock
visible replay banner
network namespace or firewall isolation

In this project, such an adapter does not exist and must not be inferred.

Replay of derived records versus native captures

The repository replays NativeCapture, not NormalizedMessage.

That is the correct default for parser and validation regression.

Native replay: tests the complete interpretation pipeline

Normalized replay: tests only downstream consumers

Both can be useful, but they answer different questions.

A normalized replay cannot expose:

parser bugs
schema interpretation errors
metadata extraction defects
unit-conversion defects
native-byte corruption

A mature system may support both, but they must use distinct replay types and manifests.

Replay is not recovery

Replay and recovery may use similar mechanics, but their authority differs.

Replay reproduces evidence for analysis or testing without live effects.
Recovery rebuilds authoritative application state after failure.

Recovery may eventually make state operational. Therefore, it requires stronger completeness proofs, checkpoint validation, and authorization.

Do not let a generic replay function silently become the recovery authority.

Conceptually:

ReplayEngine
     │ reusable iteration mechanism
     ├── DiagnosticReplayPolicy
     └── StateRecoveryPolicy

The policies must remain distinct.

Cross-industry equivalents
Domain	Equivalent
Automotive	Hardware-in-the-loop playback of CAN traffic and sensor recordings with actuation isolated
Aerospace	Telemetry and flight-recorder playback into simulation benches, never flight control
Industrial PLCs	Historian playback into a digital twin or test controller with physical outputs inhibited
Robotics	ROS bag replay with simulated clocks and hardware command topics isolated
Linux drivers	Packet capture replay, block-I/O fault injection, and recorded interrupt-sequence testing
RTOS firmware	Deterministic event-sequence injection through test queues with simulated tick control

The universal principle is:

Reproduce inputs while containing authority.

Design patterns demonstrated
Ports and Adapters: replay sends through a narrow sink contract.
Command Pattern: pause, resume, step, and stop control execution.
Virtual Clock: time behavior can be detached from wall time.
Event Sourcing: historical captures become executable input.
Manifest Pattern: replay configuration identifies one reproducible experiment.
Safety Interlock: allowable sinks are constrained independently of payload content.
Simulation Partition: replay operates in a separate authority domain.
Current repository strengths

The implementation correctly provides:

internal-only delivery
no publisher
preserved capture objects
relative timing
speed scaling
filtering
pause, step, resume, and stop
an injectable sleeper for deterministic timing tests
a narrow sink interface
Current repository limitations

A higher-assurance replay engine would still need:

explicit replay context
a versioned replay manifest
verified ordering before execution
interruptible timing waits
bounded sink-failure handling
explicit lifecycle state
simulated-clock injection
deterministic random seeds
capture-digest verification before replay
policy and software-version recording
resumable replay cursors
distinction between diagnostic replay and state recovery
auditable proof that external side effects are impossible
Architecture review questions
A preserved Production capture is replayed internally. Which checks prevent a downstream module from treating it as live Production input without falsifying the capture's original environment?
A replay is paused during a ten-minute historical delay. Should simulated time continue advancing, freeze immediately, or advance to the next message timestamp when stepped? Define the clock contract.
The sink queue saturates after capture 41. Should replay block, fail, checkpoint, or retry? How do you preserve determinism across repeated runs?
Two captures have identical receipt timestamps but different broker delivery sequences. What ordering key should replay use, and what should happen if delivery-sequence evidence is missing?
Design separate authorization contracts for diagnostic replay and authoritative state recovery. Which capabilities must recovery possess that replay must never receive?
