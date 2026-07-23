"""Inbound message value objects."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from hashlib import sha256
from types import MappingProxyType
from typing import Mapping, TypeAlias

__all__ = [
    "Environment",
    "MessageEnvelope",
    "MetadataScalar",
]



class Environment(Enum):
    """Configured source environment for an inbound message."""

    PRODUCTION = "production"
    SCENARIO = "scenario"
    UNKNOWN = "unknown"


MetadataScalar: TypeAlias = str | bytes | int | bool

def _contains_control_characters(value: str) -> bool:
    """Return whether a string contains Unicode control characters."""
    return any(ord(character) < 32 or ord(character) == 127 for character in value)


@dataclass(frozen=True, slots=True)
class MessageEnvelope:
    """Transport metadata and an unprocessed message payload."""

    payload: bytes
    received_at_utc: datetime
    environment: Environment
    connection_name: str

    destination: str | None = None
    protocol: str | None = None
    protocol_version: str | None = None
    message_id: str | None = None
    correlation_id: str | None = None
    server_timestamp: datetime | None = None
    redelivered: bool | None = None
    content_type: str | None = None
    delivery_sequence: str | int | None = None
    verified_metadata: Mapping[str, MetadataScalar] = field(default_factory=dict)

    def __post_init__(self) -> None:
        """Validate and freeze the message-envelope contract."""

        # ----- Required fields -----

        if type(self.payload) is not bytes:
            raise TypeError("payload must be exact bytes")

        if not isinstance(self.received_at_utc, datetime):
            raise TypeError("received_at_utc must be a datetime")

        if (
            self.received_at_utc.tzinfo is None
            or self.received_at_utc.utcoffset() is None
        ):
            raise ValueError("received_at_utc must be timezone-aware")

        if self.received_at_utc.utcoffset() != timedelta(0):
            raise ValueError("received_at_utc must be UTC")

        if not isinstance(self.environment, Environment):
            raise TypeError("environment must be an Environment")

        if not isinstance(self.connection_name, str):
            raise TypeError("connection_name must be a string")

        if (
            not self.connection_name
            or self.connection_name != self.connection_name.strip()
            or _contains_control_characters(self.connection_name)
        ):
            raise ValueError(
                "connection_name must be non-empty, trimmed, and contain no control characters"
            )

        # ----- Optional string fields -----

        for value, name in (
            (self.destination, "destination"),
            (self.protocol, "protocol"),
            (self.protocol_version, "protocol_version"),
            (self.message_id, "message_id"),
            (self.correlation_id, "correlation_id"),
            (self.content_type, "content_type"),
        ):
            if value is not None:
                if not isinstance(value, str):
                    raise TypeError(f"{name} must be a string")

                if not value or value != value.strip():
                    raise ValueError(f"{name} must be non-empty and trimmed")

        # ----- Optional timestamp -----

        if self.server_timestamp is not None:
            if not isinstance(self.server_timestamp, datetime):
                raise TypeError("server_timestamp must be a datetime")

            if (
                self.server_timestamp.tzinfo is None
                or self.server_timestamp.utcoffset() is None
            ):
                raise ValueError("server_timestamp must be timezone-aware")

            if self.server_timestamp.utcoffset() != timedelta(0):
                raise ValueError("server_timestamp must be UTC")

        # ----- Optional bool -----

        if (
            self.redelivered is not None
            and type(self.redelivered) is not bool
        ):
            raise TypeError("redelivered must be bool or None")

        # ----- Optional delivery sequence -----

        if self.delivery_sequence is not None:
            if type(self.delivery_sequence) is bool:
                raise TypeError("delivery_sequence must not be bool")

            if not isinstance(self.delivery_sequence, (str, int)):
                raise TypeError(
                    "delivery_sequence must be str, int, or None"
                )

        # ----- Verified metadata -----

        if not isinstance(self.verified_metadata, Mapping):
            raise TypeError("verified_metadata must be a mapping")

        if len(self.verified_metadata) > 32:
            raise ValueError(
                "verified_metadata must contain at most 32 entries"
            )

        immutable_metadata: dict[str, MetadataScalar] = {}

        for key, value in self.verified_metadata.items():
            if not isinstance(key, str):
                raise TypeError(
                    "verified_metadata keys must be strings"
                )

            if not key or key != key.strip():
                raise ValueError(
                    "verified_metadata keys must be non-empty and trimmed"
                )

            if len(key) > 64:
                raise ValueError(
                    "verified_metadata keys must be at most 64 characters"
                )

            if type(value) is str:
                try:
                    encoded_value = value.encode("utf-8")
                except UnicodeEncodeError as exc:
                    raise ValueError(
                        "verified_metadata string values must be valid UTF-8"
                    ) from exc

                if len(encoded_value) > 4096:
                    raise ValueError(
                        "verified_metadata string values must be at most "
                        "4096 bytes"
                    )

            elif type(value) is bytes:
                if len(value) > 4096:
                    raise ValueError(
                        "verified_metadata byte values must be at most "
                        "4096 bytes"
                    )

            elif type(value) is bool:
                pass

            elif type(value) is int:
                pass

            else:
                raise TypeError(
                    "verified_metadata values must be str, bytes, int, or bool"
                )

            immutable_metadata[key] = value

        object.__setattr__(
            self,
            "verified_metadata",
            MappingProxyType(immutable_metadata),
        )

    @property
    def payload_size(self) -> int:
        """Return the exact payload size in bytes."""
        return len(self.payload)

    @property
    def payload_sha256(self) -> str:
        """Return the SHA-256 digest of the exact payload bytes."""
        return sha256(self.payload).hexdigest()