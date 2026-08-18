# Architecture Decision Records

This directory records approved architecture decisions for the ShakeAlert
laboratory under the governing safety boundary and loop-engineered approval
process.

## Loop 3.2 — Runtime Architecture

- [ADR 0001: Runtime Callback Boundary](0001-runtime-callback-boundary.md)
- [ADR 0002: Bounded FIFO Queue and Single Worker](0002-bounded-fifo-queue-and-single-worker.md)
- [ADR 0003: Native Preservation Before Interpretation](0003-native-preservation-before-interpretation.md)
- [ADR 0004: Runtime Startup, Shutdown, and Failure](0004-runtime-startup-shutdown-and-failure.md)
- [ADR 0005: Scenario Receiver Service Ownership](0005-scenario-receiver-service-ownership.md)
- [ADR 0006: MessageEnvelope and Safe Event Parsing](0006-message-envelope-and-event-parsing.md)
- [ADR 0007: Repository-managed Scenario Service](0007-scenario-service-deployment.md)
- [ADR 0008: Scenario SIGTERM Coordination](0008-scenario-sigterm-coordination.md)

The listed receiver ADRs are accepted. Production activation and any new
Scenario connection remain separately authorized.
