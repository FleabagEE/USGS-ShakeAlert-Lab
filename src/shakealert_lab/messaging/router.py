"""Exact-destination message routing contracts."""

from collections.abc import Mapping
from typing import Protocol

from shakealert_lab.messaging.inbound import MessageEnvelope


class UnknownDestinationError(Exception):
    """Raised when no handler is registered for a message destination."""


class MessageHandler(Protocol):
    """Contract for inbound message handlers."""

    def handle(self, message: MessageEnvelope) -> None:
        """Handle an inbound message."""
        ...


class MessageRouter:
    """Route inbound messages by exact destination match."""

    def __init__(self, handlers: Mapping[str, MessageHandler]) -> None:
        self._handlers = handlers

    def route(self, message: MessageEnvelope) -> None:
        """Route a message to the handler registered for its destination."""
        destination = message.destination
        if destination is None:
            raise UnknownDestinationError(None)
        try:
            handler = self._handlers[destination]
        except KeyError:
            raise UnknownDestinationError(destination) from None
        handler.handle(message)
