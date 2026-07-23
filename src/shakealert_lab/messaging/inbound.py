"""Inbound message value objects."""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True, slots=True)
class MessageEnvelope:
    """Transport metadata and an unprocessed message payload."""

    topic: str
    payload: bytes
    received_at_utc: datetime
    qos: int
    retain: bool

    def __post_init__(self) -> None:
        """Validate the basic message-envelope contract."""
        if not self.topic:
            raise ValueError("topic must be non-empty")
        if not isinstance(self.payload, bytes):
            raise TypeError("payload must be bytes")
        if (
            self.received_at_utc.tzinfo is None
            or self.received_at_utc.utcoffset() is None
        ):
            raise ValueError("received_at_utc must be timezone-aware")
        if self.qos not in (0, 1, 2):
            raise ValueError("qos must be 0, 1, or 2")
