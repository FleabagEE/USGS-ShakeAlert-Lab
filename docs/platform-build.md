# Ubuntu Platform Build

## Supported baseline

The laboratory host is Ubuntu Server 24.04 LTS on x86-64. The reproducible
installer is `scripts/setup_ubuntu.sh`. It must run as root and is safe to run
repeatedly. It performs these bounded actions:

1. Refresh official Ubuntu package sources only.
2. Install the justified protocol-neutral baseline packages.
3. Create the `shakealert` system user with `/usr/sbin/nologin` and no
   administrative group membership.
4. Create `/opt/quakelogic/shakealert-lab` with the required directory tree.
5. Install the fail-closed safety preflight and passive environment file.
6. Enable Chrony, auditd, and rsyslog.
7. Audit writes and attribute changes beneath laboratory credential,
   configuration, and service-definition directories.

No protocol-specific package, credential, endpoint, receiver, or service unit
is installed. `systemd-timesyncd` is replaced by Chrony through the Ubuntu
package dependency policy.

## Validation

Run:

```bash
sudo ./scripts/setup_ubuntu.sh
sudo ./tests/security/verify_host_baseline.sh
./scripts/collect_platform_inventory.sh
```

The second installer execution on 2026-07-27 made no additional package or
account changes, demonstrating idempotence. Host validation reported zero
failures. Generated inventory is stored under ignored `evidence/` and must be
reviewed before sharing.

Applications use UTC internally. The host may display local time in
`America/Los_Angeles`; its RTC is UTC and NTP synchronization is active.
