"""Protocol-neutral heartbeat timeout monitoring."""
from dataclasses import dataclass
from datetime import datetime,timedelta,timezone
@dataclass(slots=True)
class HeartbeatMonitor:
    timeout:timedelta
    last_seen_utc:datetime|None=None
    def record(self,when_utc:datetime|None=None)->None:self.last_seen_utc=datetime.now(timezone.utc) if when_utc is None else when_utc
    def healthy(self,now_utc:datetime|None=None)->bool:
        if self.last_seen_utc is None:return False
        now=datetime.now(timezone.utc) if now_utc is None else now_utc
        return now-self.last_seen_utc<=self.timeout
