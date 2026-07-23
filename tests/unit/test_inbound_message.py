"""Tests for the inbound message-envelope contract."""

from dataclasses import FrozenInstanceError
from datetime import datetime, timezone

import pytest

from shakealert_lab.messaging.inbound import MessageEnvelope


def make_message(**changes: object) -> MessageEnvelope:
    values = {
        "topic": "eew.sys.dm.data",
        "payload": b"<event_message />",
        "received_at_utc": datetime.now(timezone.utc),
        "qos": 1,
        "retain": False,
    }
    values.update(changes)
    return MessageEnvelope(**values)  # type: ignore[arg-type]


def test_message_is_immutable() -> None:
    message = make_message()

    with pytest.raises(FrozenInstanceError):
        message.topic = "eew.sys.ha.data"  # type: ignore[misc]


def test_empty_topic_is_rejected() -> None:
    with pytest.raises(ValueError, match="topic"):
        make_message(topic="")


def test_non_bytes_payload_is_rejected() -> None:
    with pytest.raises(TypeError, match="payload"):
        make_message(payload="not bytes")


def test_naive_timestamp_is_rejected() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        make_message(received_at_utc=datetime.now())


@pytest.mark.parametrize("qos", (-1, 3))
def test_invalid_qos_is_rejected(qos: int) -> None:
    with pytest.raises(ValueError, match="qos"):
        make_message(qos=qos)


@pytest.mark.parametrize("qos", (0, 1, 2))
def test_valid_qos_is_accepted(qos: int) -> None:
    assert make_message(qos=qos).qos == qos
