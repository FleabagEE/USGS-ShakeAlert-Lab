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

This accepts only the Scenario proof-of-concept milestone. Production endpoint
facts and authorization, long-duration reliability, acknowledgment semantics,
schema/sequence characterization, operational mapping, and CUBE/PX-01 design
remain outside this acceptance.

The repository-defined Java build is verified on this host with Ubuntu Maven
3.8.7 and Java 21. The isolated dependencies/plugins are provisioned only in
`.mvn/repository` and sealed by `build-support/maven-artifacts.sha256`. Offline
compilation, all 10 JUnit tests, dependency convergence, upper-bound dependency
enforcement, duplicate-class checks, packaging, runtime-classpath verification,
and two clean-build JAR SHA-256 comparison passed.

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
