# Time Synchronization Report

Status: **HOST BASELINE PASS; USGS REQUIREMENT PENDING**

Observed on 2026-07-27 after Chrony installation:

| Attribute | Preliminary acceptance | Observed |
|---|---|---|
| Application time | UTC | UTC policy configured |
| Host display timezone | Recorded | America/Los_Angeles (PDT, UTC-07:00) |
| Hardware clock | UTC | UTC |
| Synchronization state | Synchronized | Yes |
| Service | Active | Chrony active |
| Selected source | Healthy source | Canonical NTP source, stratum 2 upstream |
| System-time error | Target ≤100 ms | Approximately 1.217 ms fast |
| Last offset | Target ≤100 ms | Approximately -0.743 ms |
| Leap status | Normal | Normal |
| Root delay | Recorded | Approximately 96.980 ms |
| Root dispersion | Recorded | Approximately 19.258 ms after convergence |

The offset satisfies the preliminary threshold. The synchronized offset and dispersion satisfy the preliminary laboratory
target; both must be compared with any actual USGS timing requirement. Raw `chronyc tracking`, `chronyc
sources -v`, and `timedatectl status` output is retained only in reviewed,
ignored evidence.
