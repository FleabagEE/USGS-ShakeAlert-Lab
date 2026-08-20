# Final Acceptance Report

**SCENARIO SERVER PROOF-OF-CONCEPT ACCEPTED**

**NOT READY TO BEGIN PRODUCTION OR CUBE IMPLEMENTATION**

The passive Scenario integration succeeded end to end using
`QuakeLogic-SA1`, ActiveMQ OpenWire over verified TLS at
`scenario.eew.shakealert.org:61612`, and the exact non-durable Topic
`eew.test_QuakeLogic-SA1.dm.data`.

The authorized M4.6 Westmoreland Event-only Scenario delivered eight Event
updates to an already authenticated, started consumer. All eight native
captures completed; no temporary capture remained; recomputed payload sizes
and SHA-256 values matched every stored record. No JMS, transport, capture,
publishing, fallback, or Production error occurred during the successful test.
Sanitized evidence is retained locally under the ignored evidence boundary.

That initial checkpoint accepted only the Scenario proof-of-concept milestone.
At that time, long-duration reliability, application acknowledgement,
schema/sequence characterization, operational mapping, and CUBE/PX-01 design
remained outside acceptance. The later sections below record the managed
receiver work completed since that checkpoint; Production remains outside it.

The repository-defined Java build is verified on this host with Ubuntu Maven
3.8.7 and Java 21. The isolated dependencies/plugins are provisioned only in
`.mvn/repository` and sealed by `build-support/maven-artifacts.sha256`. Offline
compilation, the then-current 10 JUnit tests, dependency convergence,
upper-bound dependency enforcement, duplicate-class checks, packaging,
runtime-classpath verification, and two clean-build JAR SHA-256 comparison
passed. The later `cd8e55c` suite contains 97 tests, with one historical-corpus
regression opt-in when its approved local sources are unavailable.

## Managed receiver validation — 2026-08-19

Commit `cd8e55c` passed the complete offline pre-deployment suite, and its
installed application class tree matched the repository build exactly. One
controlled, bounded Scenario activation then passed TLS/JMS authentication,
subscription readiness, and the `RUNNING` lifecycle gate with `READY=yes`.
No asynchronous JMS failure occurred, so no sanitized incident record was
created.

Normal systemd shutdown completed in the designed resource-close order, the
receiver exited with status 0, and systemd reported success. All 27 existing
captures and six existing rejection records were preserved; the bounded idle
observation created no new capture or rejection. After shutdown, no receiver
process or broker connection remained. The service stayed disabled with
`Restart=no`. Credentials and configuration were unchanged, and no Production
or CUBE connection or publishing activity occurred.

## Final current-revision live-delivery acceptance

The installed application revision remained `cd8e55c`; Git commit `f8a84f9`
was a later documentation-only change. The receiver was `READY` before the
operator requested the authorized M4.6 Westmoreland Event-only Scenario. Portal
Event ID `18718` identifies that portal operation only and is not asserted to
equal an identity inside any JMS message.

Nine deliveries completed the required sequence:

```text
MESSAGE_CALLBACK -> CAPTURE_COMMITTED -> ACKNOWLEDGEMENT_STARTED
-> ACKNOWLEDGED -> parser processing
```

The run produced nine finalized captures, eight `ShakeAlertEventUpdate`
objects, and one `ShakeAlertFollowUp`. Independent size and SHA-256 checks
passed for every capture. There were zero acknowledgement, capture, parser, or
asynchronous JMS failures; zero new rejections; and zero temporary captures.
The capture inventory increased from 27 to 36 while the rejection inventory
remained six.

Normal shutdown then produced the complete sequence from
`SHUTDOWN_REQUESTED` through ordered consumer/session/connection closure,
instance-lock release, and `STOPPED`. The process exited 0, systemd reported
success, and no receiver process or Scenario broker socket remained. The
service remained disabled with `Restart=no`; no Production/CUBE connection or
publishing occurred.

## First-stage Scenario endurance acceptance — 2026-08-19/20

The installed application revision `cd8e55c`, with repository HEAD `96f8226`
during execution, passed one controlled continuous two-hour Scenario idle
soak. The actual interval was `2:00:02`, and all 41 planned monitoring samples
succeeded. The receiver remained `RUNNING` with `READY=yes`; connected,
authenticated, subscribed, and connection-started state remained true. The
same MainPID and exactly one Scenario broker socket persisted throughout,
`NRestarts` remained zero, and there was no reconnect, retry, or failover
evidence.

No delivery occurred during the soak. Capture inventory remained 36,
rejection inventory remained six, and incident inventory remained zero. There
were no acknowledgements, temporary captures, stuck callbacks, or asynchronous
JMS, parser, capture, or acknowledgement failures. Capture and rejection
aggregate integrity remained unchanged, and no incident record was created.

Resource observations remained bounded:

| Measurement | Initial/minimum | Maximum/final |
|---|---:|---:|
| RSS | 115,116 KiB | 117,432 KiB |
| File descriptors | 15 | 15 |
| Threads | 28 | 29 |
| Cumulative CPU observation | approximately 2 s | approximately 9 s |

RSS increased by approximately two percent and plateaued well below the
defined investigation threshold. The minimum observed free capacity was
576,599,425,024 bytes and 54,500,202 inodes. No leak or resource-exhaustion
indicator was observed.

The planned stop produced `SHUTDOWN_REQUESTED -> STOPPING ->
CALLBACK_ADMISSION_CLOSED -> CONSUMER_CLOSED -> CALLBACK_DRAIN_COMPLETE ->
SESSION_CLOSED -> CONNECTION_CLOSED -> INSTANCE_LOCK_RELEASED -> STOPPED`.
The process exited 0, systemd reported success, the runtime directory was
removed, and no receiver JVM or Scenario broker socket remained. The service
stayed disabled with `Restart=no` and zero restarts. The repository working
tree remained clean throughout execution.

This evidence proves one continuous two-hour Scenario idle interval only. It
does not establish authoritative USGS heartbeat or inactivity semantics, prove
eight-hour or 24-hour endurance, or establish Production or CUBE readiness.

## Milestone decision

**MANAGED SCENARIO RECEIVER MILESTONE: COMPLETE**

This decision applies only to the authorized Scenario/development scope. It
does not establish Production or CUBE readiness, authorize another connection,
or claim complete knowledge of undocumented USGS protocol semantics.

### Scenario hardening and follow-up

- The first-stage two-hour idle soak is complete. An eight-hour extended soak
  is the next optional endurance stage; a 24-hour stage should follow only if
  separately authorized after the eight-hour result.
- Storage-capacity and controlled network-loss testing remain future,
  separately authorized hardening work.
- Persistent duplicate/redelivery history across process activations.
- Capture archival and retention operations beyond current rejection and
  incident retention.
- Continued validation of additional observed schema variants.

### Blocked on authoritative USGS facts or authorization

- Heartbeat/inactivity semantics, broker ACK confirmation and redelivery
  window, prefetch/flow control, and non-durable missed-message expectations.
- Cancellation and complete sequence/version semantics, expected rates/bursts
  and maximum payloads, and credential expiration/rotation requirements.

### Future Production/CUBE milestone

- Production endpoint, authorization, credentials, destinations, and operating
  policy.
- Any mapping, integration, or operational-output path involving CUBE/PX-01.
