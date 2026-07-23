# Architecture Decision Records

This directory records approved architecture decisions for the ShakeAlert
laboratory under the governing safety boundary and loop-engineered approval
process.

## Loop 3.2 — Runtime Architecture

- [ADR 0001: Runtime Callback Boundary](0001-runtime-callback-boundary.md)
- [ADR 0002: Bounded FIFO Queue and Single Worker](0002-bounded-fifo-queue-and-single-worker.md)
- [ADR 0003: Native Preservation Before Interpretation](0003-native-preservation-before-interpretation.md)
- [ADR 0004: Runtime Startup, Shutdown, and Failure](0004-runtime-startup-shutdown-and-failure.md)

All four Loop 3.2 ADRs are accepted. Protocol-dependent behavior remains
deferred until the native USGS protocol is verified.
