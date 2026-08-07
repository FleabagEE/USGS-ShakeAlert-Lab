## Lesson 7: bounded queue and single-worker ownership

  The main modules are:

  - src/shakealert_lab/messaging/queue_worker.py
  - src/shakealert_lab/runtime/service.py
  - docs/adr/0002-bounded-fifo-queue-and-single-worker.md

  The architectural concept is:

  > Use a bounded handoff queue to separate the transport timing domain from the processing timing domain, then use one worker to establish deterministic ownership and local
  > ordering.

  Transport callback domain
            │
            │ nonblocking submit
            ▼
      Bounded FIFO queue
            │
            │ one owner removes work
            ▼
     Processing worker domain
            │
            ├── capture
            ├── validate
            ├── route
            └── report outcome

  ## Why the architecture exists

  A transport callback and a message processor have incompatible timing needs.

  The callback must usually return promptly so the native client can continue:

  - Reading the socket
  - Processing heartbeats
  - Managing protocol state
  - Receiving later messages
  - Detecting disconnection

  Processing may involve:

  - Filesystem writes
  - Hashing
  - Validation
  - Parsing
  - Routing
  - Database operations
  - Slow storage
  - Unexpected malformed input

  Performing that work directly in the callback couples broker health to downstream execution time.

  The queue creates temporal isolation:

  arrival timing ≠ processing timing

  The transport can hand off work promptly while the worker processes it at its own bounded rate.

  ## Why the queue must be bounded

  An unbounded queue appears reliable because it rarely rejects messages.

  In reality, it converts overload into delayed process failure:

  arrival rate > processing rate
            │
            ▼
  queue grows continuously
            │
            ▼
  memory pressure
            │
            ▼
  paging, latency, allocation failure, process death

  An unbounded queue does not solve overload. It hides overload until the entire process is endangered.

  A bounded queue makes the capacity limit explicit and testable.

  capacity available → accept
  capacity exhausted → explicit saturation

  That is an embedded principle:

  > Every resource must have a declared maximum and a defined exhaustion policy.

  ## Queue capacity is part of the safety case

  Queue capacity is not simply a performance setting.

  It determines:

  - Maximum in-memory backlog
  - Maximum buffering latency
  - Worst-case drain time
  - Memory reservation
  - Number of accepted but non-durable messages
  - Burst tolerance
  - Failure detection delay

  If each message can be at most M bytes and the queue capacity is N, payload storage alone can approach:

  N × M

  But real memory consumption is larger because each entry also includes:

  - Envelope object
  - Metadata
  - Queue node
  - Hash or serialization state
  - Interpreter/runtime overhead
  - One in-progress message outside the queue

  The real outstanding-work bound is approximately:

  queue capacity + one in-progress message

  A professional capacity decision starts with measured or contractually bounded values:

  maximum arrival burst
  maximum sustained arrival rate
  worst-case processing time
  maximum payload size
  allowed buffering latency
  shutdown deadline
  available memory budget

  ## Why FIFO

  FIFO preserves local enqueue order.

  It does not prove broker order or event order. It proves only:

  > Of the messages successfully accepted by this runtime, the single worker removes them in local enqueue order.

  That narrow claim matters.

  Suppose messages arrive as:

  event update 1
  event update 2
  event final

  Priority queues, multiple workers, retry insertion, or “helpful” reordering could process final before update 2.

  FIFO avoids inventing ordering policy before protocol sequencing is understood.

  ## Why one worker

  One worker creates a single owner for downstream processing.

  Benefits include:

  - No concurrent parser access
  - No concurrent storage writes from this pipeline
  - No handler-level ordering races
  - Simple in-progress accounting
  - Predictable local ordering
  - Easier failure reproduction
  - Easier shutdown reasoning
  - Fewer locks in downstream components

  This resembles your RTOS control-task proposal:

  many producers
        │
        ▼
  bounded queue
        │
        ▼
  one owning task

  The worker serializes state mutation through ownership rather than widespread locking.

  ## Design pattern: active object

  The queue and worker together form an active object.

  Callers submit work to an object with its own execution context. They do not directly invoke the processing logic in their own thread.

  caller owns submission
  worker owns execution

  This separates invocation from execution and makes the concurrency boundary explicit.

  ## Design pattern: producer-consumer

  The transport adapter is the producer.

  The runtime worker is the consumer.

  The bounded queue is both:

  - A data structure
  - A backpressure boundary
  - An ownership-transfer boundary
  - A measurable capacity boundary

  Successful enqueue means the runtime has accepted ownership of the envelope.

  It does not mean processing completed.

  ## Accepted is not completed

  The repository deliberately distinguishes several stages:

  callback observed
        ↓
  submission accepted
        ↓
  message queued
        ↓
  message in progress
        ↓
  processed or failed
        ↓
  durably captured

  These stages must never be collapsed.

  In particular:

  accepted submission
      ≠ durable capture
      ≠ successful parsing
      ≠ safe acknowledgment

  A process crash after enqueue but before storage can still lose the message.

  This is why acknowledgment policy cannot be derived solely from successful queue insertion.

  ## Nonblocking submission

  submit() attempts immediate insertion.

  It does not wait for capacity.

  That protects the transport callback from becoming blocked behind:

  - A slow parser
  - Disk latency
  - A deadlocked handler
  - A full queue
  - A worker failure

  The tradeoff is that overload becomes an immediate rejection rather than callback latency.

  For this system, that is desirable only if rejection becomes a visible integrity failure—which is the policy you selected.

  ## Saturation as evidence

  When the queue is full, the runtime records:

  - Rejected submission
  - Queue saturation
  - Queue depth through snapshots
  - A specific QueueSaturatedError

  This is better than silently dropping oldest or newest.

  But it remains incomplete. The current runtime does not latch FAILED merely because saturation occurred.

  Under your proposed policy:

  queue saturation
        │
        ▼
  possible unrecorded message
        │
        ▼
  integrity compromised
        │
        ▼
  supervisor latches FAILED
        │
        ▼
  transport acceptance closes
        │
        ▼
  controlled disconnect

  The callback should signal the fact. The supervisor should own the state transition and disconnect command.

  ## Why silent eviction is unacceptable

  Consider dropping the oldest message:

  queue: update 1, update 2, update 3
  new:   final

  drop update 1

  The remaining stream appears valid but is incomplete.

  Dropping the newest has the same problem:

  final rejected

  The application might retain old state indefinitely without knowing the final update was lost.

  Silent loss creates false confidence. Explicit failure preserves epistemic integrity:

  > The system may not have complete data, and it knows that it may not have complete data.

  ## No automatic deduplication

  Duplicates and redeliveries are not automatically removed.

  That is correct for discovery and protocol verification.

  A duplicate could represent:

  - Broker redelivery
  - Publisher retry
  - Network reconnect behavior
  - Repeated scenario output
  - Distinct messages with identical payloads
  - Application-level duplication

  Deduplication would destroy evidence before those semantics were understood.

  Preserve first. Classify later.

  ## Message-level versus worker-level failure

  The worker separates expected message failures from unexpected execution failures.

  Expected message-level failures include:

  - Unknown destination
  - Decode failure
  - Parse failure
  - Unsupported message type

  Those affect one message and processing may continue.

  Unexpected failures include:

  - Programming assertions
  - Broken handler invariants
  - Unexpected storage exceptions
  - Worker infrastructure failures

  Those transition the runtime to FAILED.

  This distinction avoids two bad extremes.

  ### Stop on every malformed message

  That makes one bad external input a service-wide denial of service.

  ### Catch every exception and continue

  That hides programming defects and may continue with corrupted state.

  The professional decision is to define a narrow set of isolatable failures. Everything else is fatal until proven recoverable.

  ## Failure latching and queued evidence

  If the worker fails unexpectedly, queued messages remain visible.

  The runtime does not pretend they were processed or discard them during cleanup.

  A snapshot can show:

  state = FAILED
  queue_depth = 1
  in_progress = 0
  worker_failures = 1

  This matters for postmortem reasoning. Remaining work is part of the failure evidence.

  ## Draining shutdown

  Shutdown follows this sequence:

  close acceptance
        ↓
  request worker stop
        ↓
  worker finishes accepted queue
        ↓
  join until deadline
        ↓
  report drained or incomplete

  The worker exits only when:

  stop requested AND queue empty

  That means it continues processing previously accepted messages after shutdown begins.

  This is a drain policy.

  ## Why shutdown needs a deadline

  A drain can block forever if:

  - A handler deadlocks
  - Storage hangs
  - A filesystem stalls
  - Processing time is unbounded
  - A worker never exits

  The deadline bounds how long shutdown waits.

  If the deadline expires, the report preserves:

  - Remaining queued count
  - In-progress count
  - Runtime state
  - Whether draining completed

  The system does not falsely claim a clean shutdown.

  ## Embedded and RTOS equivalents

  ### RTOS queue plus task

  The direct RTOS equivalent is:

  ISR / receive callback
          │
          ├── bounded copy
          └── xQueueSendFromISR()
                    │
                    ▼
            receiver task

  The task owns parsing and state mutation.

  If the queue is full, the ISR records overflow and notifies the supervisor. It does not block indefinitely or invoke complex shutdown logic.

  ### Static allocation

  A high-assurance RTOS design would usually allocate:

  - Queue storage statically
  - Worker stack statically
  - Envelope pool statically
  - Control events statically

  That makes memory consumption knowable before activation.

  ### Ring buffer alternative

  A fixed ring buffer may replace a general queue when:

  - Element size is fixed
  - ISR latency matters
  - Allocation is forbidden
  - Memory layout must be deterministic
  - Lock-free single-producer/single-consumer behavior is useful

  The ownership and saturation policies remain the same.

  ## Linux driver equivalents

  ### Workqueues

  An interrupt or callback schedules deferred work. The workqueue performs slower processing outside interrupt context.

  ### NAPI

  Network drivers disable or moderate interrupt-driven receive work and process packets under a bounded polling budget.

  ### kfifo and ring buffers

  Drivers use bounded FIFOs to transfer data between interrupt context, kernel workers, and user space.

  ### Network queue stopping

  When capacity is exhausted, a driver may stop a queue and later wake it when resources return.

  The critical difference is that such pause/resume behavior must be supported by the transport. The application cannot invent it.

  ## Automotive patterns

  Automotive stacks commonly separate:

  CAN receive ISR
        ↓
  communication buffer
        ↓
  COM task
        ↓
  signal unpacking and application delivery

  A bounded receive buffer preserves ISR timing.

  Overflow becomes a diagnostic event, often with:

  - Lost-frame counter
  - Diagnostic trouble code
  - Channel degradation
  - Communication supervision response

  Safety-related consumers must know that message continuity was lost.

  ## Aerospace patterns

  A partitioned avionics receiver may use:

  - Fixed-size communication ports
  - Bounded message queues
  - Deterministic task periods
  - Static memory
  - Explicit overflow health events
  - Sequence counters
  - Time-partitioned processing budgets

  Queue overflow is not merely a performance warning. It may invalidate the completeness of a sensor or command stream.

  ## Engineering tradeoffs

  ### Single worker versus throughput

  One worker simplifies reasoning but limits throughput.

  If processing time is T, approximate maximum service rate is:

  1 / T messages per second

  If arrival rate exceeds service rate for long enough, saturation is inevitable.

  The correct response is not immediately “add threads.” First determine:

  - Which stage is slow?
  - Is ordering global or per event?
  - Can capture be separated from parsing?
  - Can storage batch safely?
  - Can processing be partitioned by event identity?
  - Does the protocol permit parallel handling?

  ### FIFO versus priority

  FIFO preserves local acceptance order.

  Priority processing could reduce latency for important messages, but defining importance before understanding message semantics can reorder event evolution.

  Priority belongs only after explicit requirements.

  ### In-memory queue versus durable spool

  An in-memory queue is fast and simple, but loses accepted work on process failure.

  A durable spool strengthens acceptance semantics but adds:

  - Storage latency
  - Recovery state
  - Duplicate handling
  - Corruption handling
  - Capacity management
  - Filesystem failure modes

  The architecture deferred this until delivery and acknowledgment semantics are known.

  ### Polling versus explicit wakeup

  The worker polls the queue with a short timeout.

  That simplifies stop detection but introduces:

  - Wakeup latency
  - Periodic CPU activity
  - Timing jitter
  - Arbitrary timeout tuning

  An RTOS or production design would usually use an explicit shutdown sentinel, queue closure, event notification, or condition mechanism.

  ## Important weaknesses in the current runtime

  ### Saturation does not latch failure

  The runtime counts saturation and rejects the message but remains RUNNING.

  That conflicts with your stronger integrity policy.

  A supervisor currently has no direct event interface for turning saturation into a coordinated failure.

  ### Worker can decide STOPPED

  _worker_exited() transitions the runtime from STOPPING to STOPPED.

  That violates your single lifecycle-owner design.

  The worker should report exit. The supervisor should evaluate:

  - Transport quiescence
  - Queue state
  - In-progress state
  - Storage flush
  - Acknowledgment state
  - Resource cleanup

  Only then should it commit STOPPED.

  ### Queue capacity is not a complete memory bound

  Capacity limits message count, not total bytes.

  A queue of many maximum-sized payloads can still consume substantial memory.

  A stronger model may need:

  maximum item count
  maximum total queued bytes
  maximum individual payload
  maximum metadata size

  ### No processing deadline or watchdog

  One handler can block the only worker indefinitely.

  The shutdown deadline limits how long the caller waits, but it does not terminate or recover the blocked worker.

  A supervisor needs processing-time monitoring and a defined response.

  ### Message failures lose detailed provenance

  Only counts are retained.

  A useful sanitized record might include:

  - Capture ID
  - Failure category
  - Destination classification
  - Payload hash
  - UTC time
  - Parser/version identity

  Never the raw confidential payload.

  ### Snapshot atomicity is imperfect

  Counters are protected by the runtime lock, but queue depth is maintained by the queue’s own synchronization and changes concurrently with worker activity.

  The snapshot is useful, but it is not a mathematically instantaneous picture of every subsystem unless ownership is consolidated.

  ### Daemon worker

  The worker is a daemon thread. Process exit can terminate it without finishing the drain.

  A production service should give the worker explicit lifetime ownership.

  ## A stronger conservation invariant

  For every accepted message, the system should eventually account for exactly one disposition:

  accepted
    =
  queued
  + in progress
  + processed successfully
  + isolated message failure
  + abandoned after explicit failure

  Unexpected worker failure complicates this because the current in-progress message may be neither processed nor classified as a message-level failure.

  A mature system needs an explicit abandoned or indeterminate disposition.

  Without conservation accounting, counters can look healthy while work disappears between stages.

  ## Professional firmware decision

  The deepest lesson is:

  > A queue is not just buffering. It is a contract for ownership, ordering, resource bounds, overload behavior, and shutdown accounting.

  A professional queue design specifies:

  - Who may produce
  - Who may consume
  - When ownership transfers
  - Maximum items and bytes
  - Ordering guarantees
  - Overflow behavior
  - Shutdown behavior
  - Failure accounting
  - Durability boundary
  - Interaction with acknowledgment

  ## Architecture review questions

  1. Derive a queue-capacity policy using maximum payload size, burst rate, processing time, available memory, and permitted latency. Which variable should dominate the final bound?
  2. Define the exact end-to-end saturation sequence from callback detection through supervisor failure, transport disconnect, queued-work handling, and operator evidence.
  3. A malformed message is isolated and processing continues. At what point do repeated malformed messages become a systemic failure rather than independent message failures?
  4. If measured throughput requires concurrency, design a partitioned-worker model that preserves ordering for updates belonging to the same earthquake while processing unrelated
     earthquakes in parallel.
