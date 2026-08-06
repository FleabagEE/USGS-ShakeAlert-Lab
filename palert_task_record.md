# PALERT & CUBE Edge Gateway Integration — Technical Learning Record

## 1. System Overview & Architecture

* **Hardware Stack:** Embedded Raspberry Pi SBC inside a CUBE industrial edge gateway running Ubuntu Linux.
* **Network Topology:** Dedicated local industrial switch connecting the CUBE gateway and 3 Palert triaxial accelerometers on a static local subnet (`10.1.10.x`).
* **Protocols:**
  * **Modbus TCP (Port 502):** Ingestion of real-time 3-axis acceleration telemetry streams from Palert units.
  * **MQTT (Port 1883):** External telemetry broadcasting to cloud brokers (`10.1.10.174`).
  * **SSH (Port 22):** Remote terminal management into the headless Raspberry Pi environment.

```
[ Palert #0 (10.1.10.241:502) ] ──┐
[ Palert #1 (10.1.10.242:502) ] ──┼──► [ Industrial Switch ] ──► [ CUBE Gateway (Raspberry Pi) ]
[ Palert #2 (10.1.10.243:502) ] ──┘                                       │
                                                                   (systemd service)
                                                                          │
                                                             ┌────────────┴────────────┐
                                                             ▼                         ▼
                                                    [ GPIO / Relays ]       [ MQTT Broker (1883) ]
```

## 2. Configuration Analysis (`vAlert8_success.cfg`)

Sensor Communication Block (`[PALERT_IP]`)

```ini
# NO 0
IP 10.1.10.241:502
SAMPLING_RATE 100
GAIN0 1
GAIN1 1
GAIN2 1
AUTO_OFFSET TRTC
LPF 20
PGA_TRIG_ENABLE YES
PGA_WATCH_THRESHOLD 20
PGA_WARNING_THRESHOLD 25
PGA_ACTION_THRESHOLD 80
STA_LTA_TRIG_ENABLE YES
STA_WIDTH 3
LTA_WIDTH 60
STA_LTA_THRESHOLD 3.5
STA_LTA_EVENT_TIME 20
WATCH_TIME 10
WARNING_TIME 20
EVENT_MAX_SECONDS 60
INTENSITY_AVERAGE_TH 1.5
```

### Key Parameter Explanations

#### Network & Acquisition
* `IP 10.1.10.241:502`: Modbus TCP connection targeting Palert #0 on standard industrial port 502.
* `SAMPLING_RATE 100`: 100 Hz data acquisition rate (100 samples per second).

#### Digital Signal Processing (DSP)
* `LPF 20`: 20 Hz Low-Pass Filter applied to eliminate high-frequency noise while preserving seismic frequencies.
* `GAIN0/1/2 1`: Sensor scaling multipliers for X, Y, and Z accelerometer axes.
* `AUTO_OFFSET TRTC`: Dynamic DC voltage offset zeroing to maintain accurate baseline acceleration measurements.

#### Detection Algorithms & Thresholds
* `STA_LTA_TRIG_ENABLE YES`: Enables Short-Term Average / Long-Term Average P-wave detection.
  * `STA_WIDTH 3`: Short-term window (3 seconds) calculating immediate kinetic energy.
  * `LTA_WIDTH 60`: Long-term window (60 seconds) tracking baseline ambient background noise.
  * `STA_LTA_THRESHOLD 3.5`: Triggers an event when the ratio $\frac{\text{STA}}{\text{LTA}} > 3.5$.
* `PGA_ACTION_THRESHOLD 80`: Triggers a Peak Ground Acceleration alarm when shaking reaches 80 gal ($	ext{cm/s}^2$).

## 3. System Logic & Safety Interlocks

Multi-Sensor Voting (`2-out-of-3`)

```ini
[N_WHERE_N_OUT_OF_M]
2
[M_WHERE_N_OUT_OF_M]
3
[N_OUT_OF_M_IN_SECOND]
5
```

* **Purpose:** Eliminates false alarms caused by localized physical impacts or individual sensor failure.
* **Logic:** At least $N = 2$ out of the $M = 3$ configured Palert devices must trigger within a 5-second window before the CUBE activates operational outputs.

### Output Channels
* **Physical Relays (`[RELAY_INTENSITY]`):** Drives onboard GPIO/Relays (Relays 1–4) based on calculated seismic intensity to trigger external equipment like valves or sirens.
* **Telemetry (`[MQTT_CONFIG]`):** Ingests Modbus data and broadcasts structured status payloads over MQTT to `10.1.10.174:1883`.

## 4. Verification & Deployment Workflow

1. **Layer 3 Network Diagnostic:**
   * Executed `ping 10.1.10.241` inside the CUBE SSH shell to verify physical Layer 1/2 connectivity, ARP resolution, and routing on the `10.1.10.x` subnet prior to opening Modbus sockets.
2. **Configuration Persistence:**
   * Updated `vAlert8_success.cfg` via the software management panel to configure all three Palerts (`.241`, `.242`, `.243`).
3. **Daemon Initialization:**
   * Executed `sudo reboot` to test service auto-start via systemd.
   * Verified that the `vAlert8` binary automatically binds to outbound Modbus TCP sockets on start.

## 5. Summary for Engineering Interviews

> "I performed end-to-end configuration and bring-up for a Raspberry Pi-based industrial CUBE edge gateway running embedded Ubuntu. I verified network reachability over a dedicated Ethernet switch subnet (`10.1.10.x`) using ICMP diagnostics (`ping`), then updated the gateway configuration (`vAlert8_success.cfg`) to ingest 3-axis acceleration telemetry from 3 Palert accelerometers via Modbus TCP (Port 502). I configured 20 Hz low-pass signal filtering and STA/LTA P-wave detection algorithms, tuning a 2-out-of-3 multi-sensor voting rule to prevent false triggers before driving physical relays and streaming MQTT telemetry. Finally, I tested persistent startup by rebooting the gateway and validating socket binding upon systemd service initialization."
