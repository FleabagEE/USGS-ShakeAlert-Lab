# Ubuntu Development Baseline

Preferred future laboratory host: Ubuntu Server 24.04 LTS. Ubuntu Server 22.04
LTS is acceptable only when a verified client-library requirement justifies it.
This repository does not install packages or modify the host.

Run `scripts/setup_development.sh` as a normal user. Optionally pass
`--create-venv` to create an ignored local Python virtual environment. Then run
the inventory and acceptance scripts. Record OS, kernel, architecture, Python,
Java state, OpenSSL, timezone, and time synchronization without including
hostnames, network addresses, or credentials.

Applications will use UTC internally. Initial clock target: absolute offset at
most 100 ms, warning above 250 ms, critical above 1 second. A later verified
USGS requirement supersedes these preliminary thresholds.

