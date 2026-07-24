
"""Protocol-neutral message transport abstractions."""

from shakealert_lab.transport.base import (
    ConnectionState,
    MessageSink,
    MessageTransport,
    SanitizedTransportError,
    TransportErrorCategory,
    TransportSnapshot,
    TransportState,
    TransportStopReport,
)


__all__ = [
    "ConnectionState",
    "MessageSink",
    "MessageTransport",
    "SanitizedTransportError",
    "TransportErrorCategory",
    "TransportSnapshot",
    "TransportState",
    "TransportStopReport",
]