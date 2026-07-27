"""Protocol-independent connection health model."""
from dataclasses import dataclass
from datetime import datetime
@dataclass(frozen=True, slots=True)
class ConnectionHealth:
    connection_name: str
    observed_utc: datetime
    connected: bool
    heartbeat_healthy: bool | None = None
