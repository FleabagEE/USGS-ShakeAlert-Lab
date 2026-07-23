"""Tests for exact-topic routing."""

from datetime import datetime, timezone

import pytest

from shakealert_lab.messaging.inbound import MessageEnvelope
from shakealert_lab.messaging.router import TopicRouter, UnknownTopicError


class RecordingHandler:
    def __init__(self) -> None:
        self.messages: list[MessageEnvelope] = []

    def handle(self, message: MessageEnvelope) -> None:
        self.messages.append(message)


def make_message(topic: str) -> MessageEnvelope:
    return MessageEnvelope(
        topic=topic,
        payload=b"<event_message />",
        received_at_utc=datetime.now(timezone.utc),
        qos=0,
        retain=False,
    )


def test_route_calls_exact_topic_handler() -> None:
    event_handler = RecordingHandler()
    health_handler = RecordingHandler()
    router = TopicRouter(
        {
            "eew.sys.dm.data": event_handler,
            "eew.sys.ha.data": health_handler,
        }
    )
    message = make_message("eew.sys.dm.data")

    router.route(message)

    assert event_handler.messages == [message]
    assert health_handler.messages == []


def test_route_uses_exact_matching_only() -> None:
    router = TopicRouter({"eew.sys.dm.data": RecordingHandler()})

    with pytest.raises(UnknownTopicError):
        router.route(make_message("eew.sys.dm.data.extra"))


def test_unknown_topic_is_rejected() -> None:
    router = TopicRouter({})

    with pytest.raises(UnknownTopicError):
        router.route(make_message("eew.sys.ha.data"))
