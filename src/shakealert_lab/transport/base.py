"""Message transport contracts."""

from typing import Protocol

from shakealert_lab.messaging.inbound import MessageEnvelope


class MessageSink(Protocol):
    """Contract for consumers of inbound messages."""

    def submit(self, message: MessageEnvelope) -> None:
        """Submit an inbound message."""
        ...


class MessageTransport(Protocol):
    """Lifecycle contract for a message transport."""

    def start(self) -> None:
        """Start the transport."""
        ...

    def stop(self) -> None:
        """Stop the transport."""
        ...
