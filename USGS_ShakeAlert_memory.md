# USGS ShakeAlert Lab --- Project Memory / Recovery Record

**Purpose:** Recovery checkpoint for `USGS-ShakeAlert-Lab` if chat
history is lost.\
**Final status:** **MANAGED SHAKEALERT SCENARIO RECEIVER: COMPLETE AND
SUCCESSFULLY VALIDATED** for the authorized Scenario/development scope.\
**Last documented repository commit:** `32447b2` ---
`Document two-hour Scenario endurance acceptance`\
**Application revision installed and live-tested:** `cd8e55c` ---
`Fix sanitized async JMS failure observability`

> This is historical project memory, not authorization. Current Git
> history and current USGS/ShakeAlert authorization always take
> precedence.

## Repository

-   GitHub: `FleabagEE/USGS-ShakeAlert-Lab`
-   Branch: `main`
-   Final checkpoint: local `main` and `origin/main` synchronized
    (`0 0`), working tree clean.
-   Local Git author observed: `quakelogic`.
-   Repository is a development-only/passive ShakeAlert reception
    laboratory.
-   No Production or CUBE/PX-01 readiness was claimed.

## Safety boundary

The completed milestone is limited to the authorized
Scenario/development environment.

-   No operational warning outputs or publishing.
-   No Production authorization assumed.
-   No CUBE/PX-01 operational integration.
-   Service remains disabled after controlled tests.
-   `Restart=no`.
-   Credentials, payloads, JMS IDs, broker headers, raw exception
    messages, and stack traces must not be exposed in evidence.

## Recovered bug: asynchronous JMS failure handling

When the original chat was lost, work was focused on asynchronous JMS
failure observability and teardown.

Required behavior:

1.  Classify asynchronous JMS failures into sanitized categories.
2.  Preserve the specific runtime category instead of degrading it to
    `startup`.
3.  Persist a bounded sanitized latest-incident record.
4.  Ensure diagnostic persistence can never block ordered teardown.

The remaining defect discovered during recovery was this ordering:

``` text
FAILED latched
→ ASYNC_EXCEPTION emitted
→ FAILED emitted
→ diagnostic persistence
→ coordinator notification
```

A slow/blocking filesystem write could prevent coordinator wake-up.

The fix changed it to:

``` text
FAILED latched
→ ASYNC_EXCEPTION emitted
→ FAILED emitted
→ coordinator notification
→ best-effort diagnostic persistence
```

Observability is therefore no longer on the shutdown critical path.

## Async JMS implementation

Key files:

-   `tools/AsyncJmsFailureClassifier.java`
-   `tools/SanitizedAsyncJmsIncidentStore.java`
-   `tools/ScenarioReceiverService.java`
-   `tools/ScenarioOpenWireReceiver.java`

Important tests:

-   `src/test/java/AsyncJmsFailureClassifierTest.java`
-   `src/test/java/SanitizedAsyncJmsIncidentStoreTest.java`
-   `src/test/java/ScenarioReceiverServiceTest.java`
-   `src/test/java/ScenarioOpenWireReceiverTest.java`

Sanitized classifier categories include:

-   `INACTIVITY_TIMEOUT`
-   `TRANSPORT_EOF`
-   `TRANSPORT_TIMEOUT`
-   `TLS_TRANSPORT_FAILURE`
-   `BROKER_SECURITY_FAILURE`
-   `JMS_CONNECTION_FAILURE`
-   `UNKNOWN_JMS_FAILURE`

Classification is type-based, bounded, cycle-safe, and does not depend
on arbitrary exception-message text or stack traces. Generic
`SocketException` remains `UNKNOWN_JMS_FAILURE` unless structured type
evidence supports something more specific.

## Sanitized incident store

Latest incident:

``` text
/var/lib/shakealert-scenario-receiver/incidents/async-jms-latest.json
```

Contract:

-   incident directory `0750`
-   record `0640`
-   maximum 4,096 bytes
-   temporary-file write
-   file fsync
-   atomic replacement
-   directory fsync
-   symlink-target rejection
-   one bounded latest record
-   independent of transient runtime-directory deletion

No raw exception, stack trace, credential, credential path, payload,
broker header, JMS ID, or arbitrary host path belongs in the record.

Diagnostic persistence is best-effort and must never prevent fail-closed
teardown.

## Regression coverage for the original bug

Two critical cases are covered:

-   a diagnostic sink that throws cannot prevent ordered teardown;
-   a diagnostic sink that blocks is not on the coordinator wake-up
    path.

The blocking test proves teardown can begin while persistence remains
blocked.

Focused result after the fix: **41/41 tests passed**.

## Application fix commit

``` text
cd8e55c Fix sanitized async JMS failure observability
```

This commit was pushed to `origin/main`.

## Full offline verification

Pre-deployment assessment at `cd8e55c`: **PASS**.

-   Maven/JUnit: 97 tests, 0 failures/errors, 1 intentionally skipped
-   Maven offline verify: PASS
-   Maven Enforcer: PASS
-   dependency convergence: PASS
-   upper-bound checks: PASS
-   duplicate-class checks: PASS
-   checksum manifest: PASS
-   runtime classpath verification: SUCCESS
-   Python/security: 325 tests PASS
-   safety preflight: PASS; operational outputs disabled
-   systemd static verification: PASS
-   `git diff --check`: PASS
-   reproducible JAR build: PASS with identical hashes

## Installed runtime

Reconnaissance showed the old installed runtime predated `cd8e55c`; it
lacked the classifier/store classes and still exhibited the old
`startup` failure categorization.

A controlled service-stopped installation of `cd8e55c` was performed.

Post-install:

-   installed class tree exactly matched repository build;
-   `AsyncJmsFailureClassifier.class`: installed;
-   `SanitizedAsyncJmsIncidentStore.class`: installed;
-   `ScenarioReceiverService.class`: exact match;
-   incident directory created with documented permissions;
-   credentials preserved by metadata comparison only;
-   existing captures/rejections preserved;
-   receiver remained stopped until separately authorized.

## Controlled startup validation

A first attempt was inconclusive because the command waited too long for
interactive sudo authentication; the receiver never launched.

Operational lesson: before a time-sensitive Codex sudo operation,
manually run:

``` bash
sudo -v
```

The subsequent controlled run passed:

-   startup: PASS
-   TLS/JMS authentication: PASS
-   Scenario subscription readiness: PASS
-   lifecycle: `RUNNING`
-   `READY=yes`
-   no async JMS failure
-   clean ordered shutdown
-   exit status 0
-   systemd `success`
-   final `inactive/dead`
-   zero JVMs and broker sockets
-   service disabled; `Restart=no`

## Live Scenario delivery acceptance

Authorized Scenario portal test:

-   Scenario: **M4.6 Westmoreland**
-   Selection: **Event only**
-   Portal Event ID: **18718**
-   Receiver was already READY before portal submission.

Portal Event ID 18718 is only temporal/operational correlation; do not
assume it is an identity inside JMS messages.

The portal processed updates 0--7 plus update 900.

Live receiver results:

-   `MESSAGE_CALLBACK`: 9
-   `CAPTURE_COMMITTED`: 9
-   `ACKNOWLEDGEMENT_STARTED`: 9
-   `ACKNOWLEDGED`: 9
-   ACK failures: 0
-   capture failures: 0
-   parser failures: 0
-   async JMS failures: 0
-   new rejections: 0
-   temporary/incomplete captures: 0

Parser:

-   `ShakeAlertEventUpdate`: 8
-   `ShakeAlertFollowUp`: 1

All nine deliveries proved:

``` text
MESSAGE_CALLBACK
→ CAPTURE_COMMITTED
→ ACKNOWLEDGEMENT_STARTED
→ ACKNOWLEDGED
→ parser processing
```

No application ACK occurred before durable capture.

All nine captures were finalized regular files, within policy, with
independently recomputed SHA-256 matching recorded values.

Capture inventory: **27 → 36**. Rejections remained **6**.

## Ordered shutdown

The expected sequence was observed:

``` text
SHUTDOWN_REQUESTED
→ STOPPING
→ CALLBACK_ADMISSION_CLOSED
→ CONSUMER_CLOSED
→ CALLBACK_DRAIN_COMPLETE
→ SESSION_CLOSED
→ CONNECTION_CLOSED
→ INSTANCE_LOCK_RELEASED
→ STOPPED
```

Final state:

-   exit 0
-   systemd result `success`
-   inactive/dead
-   receiver JVMs 0
-   broker sockets 0
-   automatic restarts 0
-   service disabled
-   `Restart=no`

**CURRENT-REVISION LIVE DELIVERY ACCEPTANCE: PASS**

## Milestone closure

Documentation reconciliation formally concluded:

**MANAGED SCENARIO RECEIVER MILESTONE: COMPLETE**

Milestone-closing commit:

``` text
96f8226 Close managed Scenario receiver milestone
```

This is explicitly limited to the authorized Scenario/development scope.
It does not establish Production/CUBE readiness or complete undocumented
USGS protocol semantics.

## Two-hour endurance test

First-stage live idle soak: **PASS**.

-   application revision: `cd8e55c`
-   duration: 2:00:02
-   samples: 41/41 successful
-   same MainPID throughout
-   exactly one Scenario broker socket throughout
-   `RUNNING` / `READY=yes` continuously
-   connected/authenticated/subscribed/connection_started continuously
    true
-   restarts: 0
-   deliveries: 0
-   captures: 36 → 36
-   ACKs: 0
-   rejections: 6 → 6
-   incidents: 0 → 0
-   temporary captures: 0
-   no reconnect/retry/failover
-   no async JMS/parser/capture/ACK failures
-   no stuck callback

Resources:

-   RSS initial/min: 115,116 KiB
-   RSS max/final: 117,432 KiB
-   RSS growth \~2%, then plateau
-   file descriptors: 15 throughout
-   threads: 28--29
-   CPU observation: \~2 s → 9 s
-   minimum free bytes: 576,599,425,024
-   minimum free inodes: 54,500,202

No leak or exhaustion indicator appeared. Persistent-state integrity was
unchanged. The same complete ordered shutdown passed.

This proves only one continuous two-hour Scenario idle interval; it does
not prove authoritative heartbeat semantics or 8/24-hour endurance.

Final endurance documentation commit:

``` text
32447b2 Document two-hour Scenario endurance acceptance
```

At that checkpoint: working tree clean, local/remote synchronized, 45
security/static tests passed, safety preflight passed.

## Final completed capabilities

-   managed Scenario receiver lifecycle
-   authorized TLS/JMS authentication
-   exact Scenario topic subscription/readiness
-   durable native capture
-   `CLIENT_ACKNOWLEDGE` only after durable capture
-   bounded parsing/validation
-   Event update and follow-up parsing
-   capture integrity verification
-   fail-closed behavior
-   ordered shutdown
-   async JMS classification
-   sanitized bounded persistent incident record
-   runtime failure-category preservation
-   diagnostic persistence off teardown critical path
-   full offline Java/security/reproducibility verification
-   controlled installed-runtime verification
-   nine-message real live-delivery acceptance
-   two-hour live idle endurance acceptance
-   acceptance evidence committed to GitHub

## Final conclusion

**MANAGED SHAKEALERT SCENARIO RECEIVER: COMPLETE AND SUCCESSFULLY
VALIDATED**

for the authorized Scenario/development scope.

## Future work --- separate milestones, not unfinished work

### Optional Scenario hardening

-   optional 8-hour extended idle soak
-   optional later 24-hour attended/monitored soak with separate
    authorization
-   storage-capacity monitoring and capture retention/archival
    operations
-   persistent duplicate/redelivery history across activations
-   additional evidence-driven schema variants
-   controlled network-loss testing only if separately
    authorized/isolated

### Blocked on authoritative USGS facts/authorization

-   heartbeat/inactivity semantics and keepalive expectations
-   broker ACK confirmation/redelivery window
-   prefetch/flow-control expectations
-   nondurable missed-message behavior
-   cancellation semantics
-   complete sequence/version semantics
-   broader schema/profile contracts
-   expected rates, burst sizes, maximum payloads
-   credential expiration/rotation requirements
-   whether long-lived Scenario subscriptions are expected

### Future Production/CUBE milestone

-   Production endpoint/credentials/destinations/authorization/operating
    policy
-   Production retention/reliability/ACK policy
-   CUBE/PX-01 mapping and integration
-   operational-output authorization and safety approval

## Operational reminders

-   Use `sudo -v` manually before a Codex operation that will shortly
    need sudo.
-   Do not blindly retry/restart failed Scenario connections;
    fail-closed behavior and `Restart=no` are intentional.
-   Keep installation, live testing, and documentation commits separate.
-   Because the consumer is non-durable, have the receiver READY before
    submitting an authorized Scenario portal event.
-   Do not infer portal Event IDs as JMS identities.
-   Never expose credentials, payloads, JMS IDs, broker headers, raw
    exception messages, or stack traces in evidence.
-   Preserve the distinction between documentation HEAD and the
    application revision actually installed/tested.

## Recovery instructions for a future ChatGPT/Codex session

If chat history disappears, provide this file and repository access and
say:

> Read `USGS_ShakeAlert_memory.md` and the current repository before
> making changes. Reconstruct state from Git first. The managed Scenario
> receiver milestone was completed and validated; do not reopen it as
> unfinished unless current repository evidence shows a regression or a
> new requirement is explicitly introduced.

Start with read-only Git checks:

``` bash
git status --short
git branch --show-current
git log -5 --oneline --decorate
git rev-list --left-right --count origin/main...main
```

Historical checkpoint expected from this memory:

``` text
32447b2 Document two-hour Scenario endurance acceptance
96f8226 Close managed Scenario receiver milestone
f8a84f9 Document cd8e55c controlled Scenario validation
cd8e55c Fix sanitized async JMS failure observability
```

If Git has advanced, current Git history is authoritative; use this file
as historical context.

## Bottom line

This project phase was intentionally stopped at a clean, validated
checkpoint. The receiver was not merely unit tested: it was verified
offline, reconciled after installation, authenticated in the authorized
Scenario environment, exercised with nine real deliveries, proven to
capture durably before ACK, proven to parse them, proven to shut down
correctly, and proven healthy during a two-hour idle soak.

Any Production/CUBE work or additional endurance/hardening should begin
as a **new milestone**.
