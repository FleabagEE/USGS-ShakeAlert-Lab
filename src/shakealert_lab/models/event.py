"""Event model placeholders."""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class EventCoreInfo:
    """Core information documented for a ShakeAlert event."""

    event_id: str
    magnitude: float
    latitude: float
    longitude: float
    depth_km: float
    origin_time_utc: datetime
    likelihood: float


@dataclass(frozen=True)
class ShakeAlertEvent:
    """Minimal placeholder for a ShakeAlert event message."""

    category: str
    message_type: str
    version: int
    core: EventCoreInfo
