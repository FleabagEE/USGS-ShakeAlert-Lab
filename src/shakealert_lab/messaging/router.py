"""Exact-topic message routing contracts."""

from collections.abc import Mapping
from typing import Protocol

from shakealert_lab.messaging.inbound import MessageEnvelope


class UnknownTopicError(Exception):
    """Raised when no handler is registered for a message topic."""


class MessageHandler(Protocol):
    """Contract for inbound message handlers."""

    def handle(self, message: MessageEnvelope) -> None:
        """Handle an inbound message."""
        ...


class TopicRouter:
    """Route inbound messages by exact topic match."""

    def __init__(self, handlers: Mapping[str, MessageHandler]) -> None:
        self._handlers = handlers

    def route(self, message: MessageEnvelope) -> None:
        """Route a message to the handler registered for its topic."""
        try:
            handler = self._handlers[message.topic]
        except KeyError:
            raise UnknownTopicError(message.topic) from None
        handler.handle(message)
