## Lesson 6: teardown races and late callbacks

  The case-study module is src/shakealert_lab/transport/mqtt.py.

  This is intentionally an offline adapter. It does not establish that USGS uses MQTT, and it does not connect or subscribe. Its architectural value is demonstrating a difficult
  embedded problem:

  > How do you safely stop a component when callbacks may already be executing—or may arrive after shutdown begins?

  This is fundamentally the same problem as disabling an interrupt while an ISR or deferred worker may still be running.

  ## Why this architecture exists

  A naive callback component often assumes:

  remove callback
        ↓
  callbacks have stopped

  That assumption is false.

  At the moment callback detachment begins, several things may already be true:

  - A callback is executing on another thread.
  - A thread has copied the callback reference but has not invoked it.
  - Callback registration is still in progress.
  - A native property setter is blocked.
  - The native library is synchronously invoking the callback during registration.
  - Another component has replaced the callback.
  - Shutdown has been requested by several threads.
  - The shutdown deadline has already expired.

  The adapter exists to make those races explicit.

  ## The central rule: close acceptance before detachment

  Shutdown begins by closing the logical acceptance gate:

  accepting_callbacks = false

  Only afterward does it attempt to detach callbacks from the native client.

  Shutdown requested
         │
         ▼
  Close acceptance gate
         │
         ├── late callbacks may still execute
         │       └── rejected without submission
         │
         ▼
  Detach native callbacks
         │
         ▼
  Wait or report incomplete quiescence

  This ordering is essential.

  If the adapter detached first and closed acceptance later, a callback racing with detachment could still enter the application queue during shutdown.

  The acceptance gate provides the shutdown linearization point:

  > After this instant, no newly observed callback may become accepted application work.

  Physical detachment is cleanup. The logical gate establishes safety.

  ## Why callback detachment is insufficient

  A native client can expose a callback through an ordinary field, but clearing that field does not revoke references already copied elsewhere.

  Consider:

  Thread A                         Thread B
  --------                         --------
  copy callback reference
                                   clear client callback
  invoke copied callback

  Thread B successfully detached the callback, yet Thread A still invokes it.

  That is why the callback itself checks whether acceptance remains open.

  This is defense in depth:

  - Native detachment reduces future invocations.
  - Callback-local gating neutralizes already captured invocations.
  - In-progress accounting determines eventual quiescence.

  ## Design pattern: acceptance gate

  The adapter separates:

  - Callback can execute
  - Callback may submit work

  A late callback is allowed to enter enough code to record that it happened, but it cannot cross the application handoff boundary.

  This is stronger than trying to guarantee that late callbacks never occur.

  Professional firmware usually designs around this distinction:

  execution possible ≠ effect permitted

  ## Embedded and RTOS equivalent

  For an interrupt-driven peripheral:

  1. Mark the driver as no longer accepting input.
  2. Disable the peripheral interrupt source.
  3. Disable or mask the CPU interrupt.
  4. Synchronize with any ISR already in progress.
  5. Cancel or drain deferred work.
  6. Release hardware and memory only after quiescence.

  A late ISR may still run during steps 2–4. It must observe the disabled state and avoid touching resources being torn down.

  In an RTOS, this may involve:

  - An atomic acceptance flag
  - Interrupt masking
  - An in-flight ISR/task counter
  - An event group or semaphore signaling zero in-flight work
  - Queue closure
  - A bounded teardown deadline

  ## Linux driver equivalent

  Linux drivers repeatedly use this pattern during device removal and interface shutdown:

  stop new work
  disable event source
  synchronize with active handler
  cancel deferred work
  release resources

  Conceptual equivalents include:

  - Stopping a network transmit queue
  - Disabling NAPI polling
  - Disabling an IRQ
  - Synchronizing with an active interrupt handler
  - Canceling workqueues synchronously
  - Waiting for reference counts to reach zero

  The rule is always the same:

  > Memory and dependent resources cannot be released merely because the source was disabled.

  You must prove no existing execution context can still use them.

  ## Automotive equivalent

  In an automotive communication stack, shutting down a bus channel may require:

  - Rejecting new transmit requests
  - Disabling receive indications
  - Stopping bus activity
  - Waiting for active callbacks to return
  - Reporting bus-off or shutdown state
  - Preserving diagnostic counters
  - Preventing communication-manager reactivation during teardown

  A late CAN reception indication must not enter an application after the channel has crossed into its shutdown state.

  ## Aerospace equivalent

  A redundant communication channel may be inhibited while:

  - A DMA transfer is completing
  - A receive interrupt is pending
  - A message-validation task is active
  - Health monitoring is sampling channel state

  The channel cannot be declared quiescent until all execution contexts are accounted for. Conservative reporting is preferred over a false “stopped” declaration.

  ## Registration is also a race

  The adapter handles a less obvious problem: callback registration itself may block or invoke application code.

  A foreign setter such as “install this callback” is executable behavior. It may:

  - Acquire native locks
  - Allocate memory
  - Block
  - Throw
  - Invoke the callback synchronously
  - Re-enter the adapter

  Therefore, the adapter does not hold its internal lock while calling native registration operations.

  Instead:

  1. Under lock, transition to STARTING.
  2. Mark registration in progress.
  3. Release the lock.
  4. Call the foreign client.
  5. Reacquire the lock.
  6. Commit success or failure.
  7. Honor any shutdown request that arrived meanwhile.

  This is a split-phase operation.

  ## Design pattern: prepare, execute, commit

  The lifecycle operation is divided into three phases:

  Prepare under local lock
          │
          ▼
  Execute foreign operation without local lock
          │
          ▼
  Commit result under local lock

  This prevents a foreign library from running while the adapter’s state lock is held.

  It reduces:

  - Deadlock risk
  - Reentrant corruption
  - Long lock hold times
  - Blocking of status queries
  - Blocking of concurrent stop requests

  The price is increased state-machine complexity. The adapter must remember that registration is in progress and reconcile shutdown afterward.

  ## Synchronous callback during registration

  A particularly adversarial test makes the native callback setter invoke the callback before registration has returned.

  At that moment, the adapter is still STARTING and acceptance remains closed.

  The callback is counted but rejected.

  This is correct. The adapter has not yet committed itself to RUNNING.

  The general rule is:

  > Never expose partially initialized state as operational merely because foreign code called back early.

  This appears frequently in hardware drivers where enabling a peripheral can immediately assert an interrupt.

  ## Stop during start

  Another race is:

  Thread A: start registration
  Thread B: request stop

  The adapter cannot safely detach something whose registration has not completed.

  It records a pending detachment request. When registration completes, exactly one caller claims responsibility for launching detachment.

  This is a deferred cleanup pattern.

  The state must remember:

  - Registration is in progress.
  - Detachment was requested.
  - Detachment has started.
  - Detachment has finished.

  Those fields look verbose, but each represents a distinct concurrency fact.

  Collapsing them into one Boolean would create ambiguous states.

  ## Exactly-once cleanup ownership

  Several threads may call stop() concurrently.

  Only one may initiate callback detachment.

  The adapter uses a claim operation under the lock:

  detachment requested
  and registration finished
  and detachment not started
          │
          ▼
  this caller owns detachment

  This resembles atomic ownership acquisition.

  Without it, concurrent stop calls could:

  - Detach repeatedly
  - Race while modifying native callbacks
  - Extend deadlines
  - Report inconsistent completion
  - Overwrite another component’s callback

  ## Do not remove callbacks you do not own

  Before clearing a callback, the adapter checks whether the native client still contains the exact callback instance it installed.

  That protects this sequence:

  adapter installs callback A
  another owner installs callback B
  adapter shuts down

  The adapter must not clear callback B.

  This demonstrates resource ownership discipline:

  > Cleanup may release only resources still owned by the component performing cleanup.

  The adapter stores its bound callback objects so identity comparisons remain stable.

  ## In-flight callback accounting

  Every message callback increments an in-progress count on entry and decrements it in a guaranteed exit path.

  This allows shutdown to distinguish:

  callback detached, none active

  from:

  callback detached, one old callback still executing

  A quiescent state requires both:

  - Callback registration is gone.
  - In-progress count is zero.

  This resembles reference counting around ISR/deferred-work lifetime.

  ## Queue saturation is not a native transport error

  The adapter distinguishes:

  - Native callback mapping failed
  - Runtime rejected submission
  - Runtime queue saturated
  - Unexpected sink failure

  Queue saturation increments rejection and saturation counters. It does not replace the latest native transport error.

  This preserves fault-domain separation:

  broker/client failure      → transport fault
  invalid callback mapping   → adapter boundary fault
  queue full                 → runtime capacity fault
  unexpected sink exception  → application/runtime failure

  A useful diagnostic system reports the responsible layer rather than labeling every failure “connection error.”

  ## Failure latching

  An unexpected sink failure transitions the adapter to FAILED and closes acceptance.

  It does not catch the error, increment a counter, and continue indefinitely.

  This demonstrates a professional distinction:

  - Expected rejection is part of the contract.
  - Queue saturation is a known capacity condition.
  - An arbitrary sink exception indicates a violated assumption.

  Continuing after an unknown exception could create partial processing or inconsistent accounting.

  ## Ordinary exceptions versus fatal exceptions

  The adapter sanitizes ordinary operational exceptions.

  It does not broadly swallow all process-level failures.

  This matters because overly broad exception handling can conceal:

  - Process termination
  - Test interruption
  - Severe runtime failures
  - Programming defects

  In embedded terms, not every exception belongs to local recovery. Some failures must reach the system supervisor or watchdog.

  ## Inactive native-client construction

  src/shakealert_lab/transport/paho_factory.py constructs an inactive client only.

  It deliberately does not accept:

  - Host
  - Port
  - Credentials
  - Topic
  - TLS settings
  - Logging
  - Subscription
  - Reconnection policy from callers

  It also disables automatic reconnection.

  The engineering principle is:

  > Construction must not secretly activate external behavior.

  Object construction, connection, authentication, and subscription should be separate lifecycle events with separate authorization gates.

  ## Engineering tradeoffs

  ### Locking in callback paths

  A lock makes counter updates and state observations consistent.

  But in firmware, a general recursive lock in a callback or ISR-equivalent path may be unacceptable because of:

  - Priority inversion
  - Unbounded blocking
  - Scheduler dependence
  - Deadlock
  - Latency jitter

  An RTOS implementation might use:

  - Short interrupt-masked critical sections
  - Atomics
  - Lock-free counters
  - Single-writer ownership
  - A ring buffer with explicit memory ordering

  ### Copying into an envelope

  Copying establishes ownership and immutability, but costs CPU time and memory bandwidth.

  Zero-copy designs reduce copying but require buffer pools, reference counts, ownership transfer, and release protocols. They are faster but harder to prove correct.

  ### Asynchronous detachment

  Asynchronous cleanup keeps stop() responsive when a native setter blocks.

  The cost is that cleanup continues after stop() returns, requiring conservative STOPPING state and external supervision.

  ### Detectable saturation

  The adapter counts saturation but does not itself fail immediately.

  That keeps policy outside the protocol adapter, but it means a higher layer must convert saturation into the explicit integrity failure you selected. Otherwise counters alone
  could become silent loss with better bookkeeping.

  ## Important weaknesses in the current adapter

  ### The deadline is recorded but not actively consumed

  The adapter preserves the earliest deadline, but it does not wait until that deadline or transition based on elapsed time.

  It launches detachment asynchronously and returns a conservative report.

  This is a useful skeleton, not a complete shutdown implementation.

  ### Clean completion is not fully established

  After callback detachment finishes, the adapter marks detachment complete, but the visible lifecycle does not clearly transition to STOPPED, and callback-registration ownership is
  not fully cleared for the quiescence test.

  A production implementation needs a single completion routine that atomically:

  - Marks callbacks detached
  - Clears registration ownership
  - Checks in-flight count
  - Transitions to STOPPED when safe
  - Signals waiters

  ### Daemon cleanup thread

  The detachment worker is a daemon thread. Process exit can terminate it before cleanup completes.

  That is acceptable for an offline experiment but weak for controlled shutdown.

  A production design would give cleanup explicit lifetime ownership.

  ### No generation number

  A callback captured from an earlier start generation is rejected after stop because acceptance is closed. But if restart were permitted, an old callback could potentially arrive
  during a later generation.

  A generation token would distinguish callbacks from different activation epochs.

  The current design avoids this by latching failure and tightly controlling restart, but generation identity is still a valuable pattern.

  ### Connection result interpretation is minimal

  Treating a native reason code of zero as connected is only a narrow mapping. It does not prove TLS, authentication, authorization, or subscription readiness.

  ### Saturation policy remains incomplete

  Your chosen architecture says saturation should become an explicit integrity failure. The adapter currently records it but remains running.

  The runtime or supervisor must define the escalation threshold—possibly one saturation event.

  ## Professional firmware decision

  The deepest lesson is this:

  > Shutdown is not a function call. It is a protocol among event sources, callbacks, workers, queues, and resource owners.

  A professional teardown design defines:

  - The exact instant new work becomes forbidden
  - How late work is rejected
  - How in-flight work is counted
  - How event sources are disabled
  - Who owns cleanup
  - How completion is proven
  - What deadline applies
  - What happens when quiescence cannot be proven

  ## Architecture review questions

  1. Design the complete transition from STOPPING to STOPPED. Which exact conditions must hold, and which thread or component performs the transition?
  2. If one queue saturation means possible message loss, should the callback immediately latch FAILED, request transport disconnection, or only notify the runtime supervisor?
     Define ownership of that decision.

  3. Suppose an old callback from activation generation 7 arrives after generation 8 starts. How would you bind callbacks to a generation and reject the stale one without rejecting
     valid generation-8 callbacks?

  4. In an RTOS implementation, replace the recursive lock and dynamically created detachment thread. What primitives and task ownership model would you use?
