# Phase 1 Acceptance Report

Decision: **NO-GO PENDING ENDPOINT-SPECIFIC NETWORK INPUTS AND FORMAL REVIEW**

## Completed work

- Idempotent `scripts/setup_ubuntu.sh` implemented and executed twice.
- Ubuntu 24.04 LTS, x86-64, kernel, Python, Java, OpenSSL, timezone, and clock
  state captured in ignored evidence.
- All justified protocol-neutral baseline packages installed; no messaging
  stack was installed.
- Chrony enabled and synchronized. On 2026-07-27 its leap status was `Normal`,
  system-time error was approximately 1.217 ms, and last measured offset was
  approximately -0.743 ms, within the preliminary 100 ms target.
- auditd and rsyslog enabled; host validation passed with zero failures.
- No credential was installed or inspected and no USGS endpoint was contacted.

## Exit criteria

- [x] Supported Ubuntu release, kernel, architecture, runtime versions, and
  timezone recorded.
- [x] Chrony is active with a selected source and normal leap status.
- [x] Observed clock offset is within the preliminary 100 ms requirement.
- [x] Required baseline packages and versions are recorded.
- [x] Idempotent host setup and automated host validation exist.
- [ ] Production and scenario destination hostnames and ports are authoritative.
- [ ] Required destination DNS and outbound routes are verified after approval.
- [ ] Source public IP, VPN/proxy, allow-list, and SNI requirements are recorded.
- [ ] Named reviewers approve Phase 0 and Phase 1 evidence.
- [x] No credential values were required, printed, or stored.

## Test evidence

- Repository security checks: zero failures.
- Host baseline checks: zero failures.
- Python framework tests: 269 passed.
- Provisioning idempotence: second run installed zero packages and preserved
  the account, permissions, safety configuration, and audit rules.

## Unresolved questions

Endpoint-specific network requirements cannot be inferred from a tutorial.
The production endpoint is unknown, and the scenario wire protocol, assigned
port, assigned destination, VPN/proxy policy, allow-list, and TLS SNI rules
remain unverified.

## Gate decision

Do not begin credential/endpoint discovery or any controlled connection test
until protected access materials, formal Phase 0/1 review, and explicit
endpoint-specific connectivity authorization are available.
