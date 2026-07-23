"""Tests for the inbound message-envelope contract."""

from dataclasses import FrozenInstanceError
from datetime import datetime, timedelta, timezone

import pytest

from shakealert_lab.messaging.inbound import Environment, MessageEnvelope


def make_message(**changes: object) -> MessageEnvelope:
    values = {
        "payload": b"<event_message />",
        "received_at_utc": datetime.now(timezone.utc),
        "environment": Environment.SCENARIO,
        "connection_name": "scenario-primary",
        "destination": "eew.sys.dm.data",
    }
    values.update(changes)
    return MessageEnvelope(**values)  # type: ignore[arg-type]


def test_message_is_immutable() -> None:
    message = make_message()

    with pytest.raises(FrozenInstanceError):
        message.connection_name = "changed"  # type: ignore[misc]


def test_protocol_neutral_identity_is_preserved() -> None:
    message = make_message()

    assert message.environment is Environment.SCENARIO
    assert message.connection_name == "scenario-primary"
    assert message.destination == "eew.sys.dm.data"


@pytest.mark.parametrize(
    "payload",
    ("not bytes", bytearray(b"mutable"), memoryview(b"view")),
)
def test_non_bytes_payload_is_rejected(payload: object) -> None:
    with pytest.raises(TypeError, match="payload"):
        make_message(payload=payload)


def test_naive_received_timestamp_is_rejected() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        make_message(received_at_utc=datetime.now())


def test_non_utc_received_timestamp_is_rejected() -> None:
    offset = timezone(timedelta(hours=1))

    with pytest.raises(ValueError, match="UTC"):
        make_message(received_at_utc=datetime.now(offset))


def test_naive_server_timestamp_is_rejected() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        make_message(server_timestamp=datetime.now())


def test_non_utc_server_timestamp_is_rejected() -> None:
    offset = timezone(timedelta(hours=-1))

    with pytest.raises(ValueError, match="UTC"):
        make_message(server_timestamp=datetime.now(offset))


def test_invalid_environment_is_rejected() -> None:
    with pytest.raises(TypeError, match="environment"):
        make_message(environment="scenario")


@pytest.mark.parametrize(
    "connection_name",
    ("", " scenario", "scenario ", "scenario\nforged"),
)
def test_invalid_connection_name_is_rejected(connection_name: str) -> None:
    with pytest.raises(ValueError, match="connection_name"):
        make_message(connection_name=connection_name)


def test_payload_size_is_derived() -> None:
    message = make_message(payload=b"abc")

    assert message.payload_size == 3


def test_payload_sha256_is_derived() -> None:
    message = make_message(payload=b"abc")

    assert message.payload_sha256 == (
        "ba7816bf8f01cfea414140de5dae2223"
        "b00361a396177a9cb410ff61f20015ad"
    )


def test_metadata_is_defensively_copied_and_immutable() -> None:
    metadata = {"source": "native"}

    message = make_message(verified_metadata=metadata)
    metadata["source"] = "changed"

    assert message.verified_metadata["source"] == "native"

    with pytest.raises(TypeError):
        message.verified_metadata["source"] = "changed"  # type: ignore[index]


@pytest.mark.parametrize("value", ([], {}, object(), bytearray(b"x")))
def test_unsupported_metadata_values_are_rejected(value: object) -> None:
    with pytest.raises(TypeError, match="verified_metadata values"):
        make_message(verified_metadata={"field": value})


def test_boolean_delivery_sequence_is_rejected() -> None:
    with pytest.raises(TypeError, match="delivery_sequence"):
        make_message(delivery_sequence=True)
