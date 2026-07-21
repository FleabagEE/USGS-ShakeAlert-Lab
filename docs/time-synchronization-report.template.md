# Time Synchronization Report Template

Status: **PENDING DEDICATED-HOST EVIDENCE**

| Attribute | Preliminary acceptance | Observed |
|---|---|---|
| Application time | UTC | Pending |
| System UTC offset | Recorded | Pending |
| Synchronization state | Synchronized | Pending |
| Selected source | Healthy approved source | Pending |
| Absolute clock offset | Target ≤100 ms | Pending |
| Warning / critical | >250 ms / >1 s | Policy pending authoritative input |
| Leap status | Normal | Pending |
| Estimated precision | Recorded | Pending |

Required local commands: `timedatectl status`, `chronyc tracking`, and
`chronyc sources -v`. Repository-only development results are not final host
acceptance evidence.

