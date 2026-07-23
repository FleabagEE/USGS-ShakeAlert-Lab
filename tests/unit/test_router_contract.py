"""Tests for exact-destination routing."""

from datetime import datetime, timezone

import pytest

from shakealert_lab.messaging.inbound import Environment, MessageEnvelope
from shakealert_lab.messaging.router import (
    MessageRouter,
    UnknownDestinationError,
)


class RecordingHandler:
    def __init__(self) -> None:
        self.messages: list[MessageEnvelope] = []

    def handle(self, message: MessageEnvelope) -> None:
        self.messages.append(message)


def make_message(destination: str | None) -> MessageEnvelope:
    return MessageEnvelope(
        payload=b"<event_message />",
        received_at_utc=datetime.now(timezone.utc),
        environment=Environment.SCENARIO,
        connection_name="scenario-primary",
        destination=destination,
    )


def test_route_calls_exact_destination_handler() -> None:
    event_handler = RecordingHandler()
    health_handler = RecordingHandler()
    router = MessageRouter(
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
    router = MessageRouter({"eew.sys.dm.data": RecordingHandler()})

    with pytest.raises(UnknownDestinationError):
        router.route(make_message("eew.sys.dm.data.extra"))


def test_route_is_case_sensitive() -> None:
    router = MessageRouter({"eew.sys.dm.data": RecordingHandler()})

    with pytest.raises(UnknownDestinationError):
        router.route(make_message("EEW.SYS.DM.DATA"))


def test_unknown_destination_is_rejected() -> None:
    router = MessageRouter({})

    with pytest.raises(UnknownDestinationError):
        router.route(make_message("eew.sys.ha.data"))


def test_absent_destination_is_rejected() -> None:
    router = MessageRouter({})

    with pytest.raises(UnknownDestinationError) as raised:
        router.route(make_message(None))

    assert raised.value.args == (None,)
