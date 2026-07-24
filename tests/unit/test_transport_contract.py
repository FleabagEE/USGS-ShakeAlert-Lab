"""Tests for protocol-neutral transport contracts."""

import ast
import inspect
from dataclasses import FrozenInstanceError, fields
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Protocol

import pytest

import shakealert_lab.transport as transport_package
import shakealert_lab.transport.base as transport_base
from shakealert_lab.messaging.inbound import Environment, MessageEnvelope
from shakealert_lab.runtime.service import RuntimeService
from shakealert_lab.transport import (
    ConnectionState,
    MessageSink,
    MessageTransport,
    SanitizedTransportError,
    TransportErrorCategory,
    TransportSnapshot,
    TransportState,
    TransportStopReport,
)


APPROVED_EXPORTS = {
    "ConnectionState",
    "MessageSink",
    "MessageTransport",
    "SanitizedTransportError",
    "TransportErrorCategory",
    "TransportSnapshot",
    "TransportState",
    "TransportStopReport",
}


def make_error(**changes: object) -> SanitizedTransportError:
    values = {
        "category": TransportErrorCategory.CONNECTION,
        "exception_type": "ConnectionError",
        "summary": "transport connection failed",
        "occurred_at_utc": datetime.now(timezone.utc),
    }
    values.update(changes)
    return SanitizedTransportError(**values)  # type: ignore[arg-type]


def make_snapshot(**changes: object) -> TransportSnapshot:
    values = {
        "state": TransportState.RUNNING,
        "connection_state": ConnectionState.UNKNOWN,
        "environment": Environment.SCENARIO,
        "connection_name": "scenario-primary",
        "callbacks_received": 4,
        "submissions_accepted": 2,
        "submissions_rejected": 1,
        "queue_saturations": 1,
        "mapping_failures": 1,
        "callbacks_quiescent": False,
        "latest_error": None,
    }
    values.update(changes)
    return TransportSnapshot(**values)  # type: ignore[arg-type]


def make_stop_report(**changes: object) -> TransportStopReport:
    values = {
        "state": TransportState.STOPPED,
        "callbacks_quiescent": True,
        "callbacks_in_progress": 0,
        "latest_error": None,
    }
    values.update(changes)
    return TransportStopReport(**values)  # type: ignore[arg-type]


def test_transport_state_members_and_values_are_exact() -> None:
    assert {member.name: member.value for member in TransportState} == {
        "STOPPED": "stopped",
        "STARTING": "starting",
        "RUNNING": "running",
        "STOPPING": "stopping",
        "FAILED": "failed",
    }


def test_connection_state_members_and_values_are_exact() -> None:
    assert {member.name: member.value for member in ConnectionState} == {
        "UNKNOWN": "unknown",
        "CONNECTING": "connecting",
        "CONNECTED": "connected",
        "DISCONNECTED": "disconnected",
        "DEGRADED": "degraded",
    }


def test_transport_error_category_members_and_values_are_exact() -> None:
    assert {
        member.name: member.value for member in TransportErrorCategory
    } == {
        "STARTUP": "startup",
        "SHUTDOWN": "shutdown",
        "CONNECTION": "connection",
        "CALLBACK_MAPPING": "callback_mapping",
        "CLIENT_CALLBACK": "client_callback",
        "UNKNOWN": "unknown",
    }


@pytest.mark.parametrize(
    ("value", "attribute"),
    (
        (make_error(), "summary"),
        (make_snapshot(), "connection_name"),
        (make_stop_report(), "callbacks_in_progress"),
    ),
)
def test_result_objects_are_frozen_and_slotted(
    value: object,
    attribute: str,
) -> None:
    assert not hasattr(value, "__dict__")

    with pytest.raises(FrozenInstanceError):
        setattr(value, attribute, object())


def test_sanitized_error_accepts_valid_values() -> None:
    occurred_at = datetime.now(timezone.utc)
    error = make_error(occurred_at_utc=occurred_at)

    assert error.category is TransportErrorCategory.CONNECTION
    assert error.exception_type == "ConnectionError"
    assert error.summary == "transport connection failed"
    assert error.occurred_at_utc is occurred_at


def test_sanitized_error_rejects_invalid_category_type() -> None:
    with pytest.raises(TypeError, match="category"):
        make_error(category="connection")


@pytest.mark.parametrize(
    "exception_type",
    ("", " Error", "Error ", "Line\nError", "module.Error", "not valid"),
)
def test_sanitized_error_rejects_invalid_exception_type(
    exception_type: str,
) -> None:
    with pytest.raises(ValueError, match="exception_type"):
        make_error(exception_type=exception_type)


def test_sanitized_error_rejects_non_string_exception_type() -> None:
    with pytest.raises(TypeError, match="exception_type"):
        make_error(exception_type=object())


def test_sanitized_error_accepts_128_character_identifier() -> None:
    error = make_error(exception_type="E" * 128)

    assert error.exception_type == "E" * 128


def test_sanitized_error_rejects_exception_type_over_128_characters() -> None:
    with pytest.raises(ValueError, match="128"):
        make_error(exception_type="E" * 129)


@pytest.mark.parametrize(
    "summary",
    ("", " summary", "summary ", "line\nbreak", "tab\tvalue"),
)
def test_sanitized_error_rejects_invalid_summary(summary: str) -> None:
    with pytest.raises(ValueError, match="summary"):
        make_error(summary=summary)


def test_sanitized_error_rejects_non_string_summary() -> None:
    with pytest.raises(TypeError, match="summary"):
        make_error(summary=object())


def test_sanitized_error_accepts_256_character_summary() -> None:
    error = make_error(summary="s" * 256)

    assert error.summary == "s" * 256


def test_sanitized_error_rejects_summary_over_256_characters() -> None:
    with pytest.raises(ValueError, match="256"):
        make_error(summary="s" * 257)


def test_sanitized_error_rejects_non_datetime_timestamp() -> None:
    with pytest.raises(TypeError, match="occurred_at_utc"):
        make_error(occurred_at_utc="now")


def test_sanitized_error_rejects_naive_timestamp() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        make_error(occurred_at_utc=datetime.now())


def test_sanitized_error_rejects_non_utc_timestamp() -> None:
    non_utc = timezone(timedelta(hours=1))

    with pytest.raises(ValueError, match="UTC"):
        make_error(occurred_at_utc=datetime.now(non_utc))


@pytest.mark.parametrize(
    ("field_name", "value"),
    (
        ("state", "running"),
        ("connection_state", "unknown"),
        ("environment", "scenario"),
    ),
)
def test_snapshot_rejects_invalid_enum_and_environment_types(
    field_name: str,
    value: object,
) -> None:
    with pytest.raises(TypeError, match=field_name):
        make_snapshot(**{field_name: value})


@pytest.mark.parametrize(
    "connection_name",
    ("", " scenario", "scenario ", "scenario\nforged"),
)
def test_snapshot_rejects_invalid_connection_name(
    connection_name: str,
) -> None:
    with pytest.raises(ValueError, match="connection_name"):
        make_snapshot(connection_name=connection_name)


def test_snapshot_rejects_non_string_connection_name() -> None:
    with pytest.raises(TypeError, match="connection_name"):
        make_snapshot(connection_name=object())


@pytest.mark.parametrize(
    "field_name",
    (
        "callbacks_received",
        "submissions_accepted",
        "submissions_rejected",
        "queue_saturations",
        "mapping_failures",
    ),
)
@pytest.mark.parametrize("value", (True, 1.5, "1"))
def test_snapshot_counter_rejects_bool_and_non_int_values(
    field_name: str,
    value: object,
) -> None:
    with pytest.raises(TypeError, match=field_name):
        make_snapshot(**{field_name: value})


@pytest.mark.parametrize(
    "field_name",
    (
        "callbacks_received",
        "submissions_accepted",
        "submissions_rejected",
        "queue_saturations",
        "mapping_failures",
    ),
)
def test_snapshot_counter_rejects_negative_values(field_name: str) -> None:
    with pytest.raises(ValueError, match=field_name):
        make_snapshot(**{field_name: -1})


@pytest.mark.parametrize("value", (0, 1, "false", None))
def test_snapshot_rejects_non_bool_quiescence(value: object) -> None:
    with pytest.raises(TypeError, match="callbacks_quiescent"):
        make_snapshot(callbacks_quiescent=value)


def test_snapshot_rejects_invalid_latest_error_type() -> None:
    with pytest.raises(TypeError, match="latest_error"):
        make_snapshot(latest_error=object())


def test_snapshot_accepts_sanitized_latest_error() -> None:
    error = make_error()

    assert make_snapshot(latest_error=error).latest_error is error


def test_snapshot_rejects_saturation_exceeding_rejections() -> None:
    with pytest.raises(ValueError, match="queue_saturations"):
        make_snapshot(submissions_rejected=0, queue_saturations=1)


def test_snapshot_rejects_outcomes_exceeding_callbacks() -> None:
    with pytest.raises(ValueError, match="callback outcomes"):
        make_snapshot(
            callbacks_received=2,
            submissions_accepted=1,
            submissions_rejected=1,
            queue_saturations=0,
            mapping_failures=1,
        )


def test_snapshot_accepts_incomplete_callback_accounting() -> None:
    snapshot = make_snapshot(
        callbacks_received=4,
        submissions_accepted=1,
        submissions_rejected=1,
        queue_saturations=0,
        mapping_failures=1,
    )

    assert snapshot.callbacks_received == 4


@pytest.mark.parametrize(
    "state",
    (TransportState.STARTING, TransportState.RUNNING),
)
def test_stop_report_rejects_non_stop_states(state: TransportState) -> None:
    with pytest.raises(ValueError, match="stop report state"):
        make_stop_report(state=state)


def test_stop_report_rejects_invalid_state_type() -> None:
    with pytest.raises(TypeError, match="state"):
        make_stop_report(state="stopped")


@pytest.mark.parametrize("value", (0, 1, "true", None))
def test_stop_report_rejects_non_bool_quiescence(value: object) -> None:
    with pytest.raises(TypeError, match="callbacks_quiescent"):
        make_stop_report(callbacks_quiescent=value)


@pytest.mark.parametrize("value", (True, 1.5, "1"))
def test_stop_report_rejects_bool_and_non_int_in_progress(
    value: object,
) -> None:
    with pytest.raises(TypeError, match="callbacks_in_progress"):
        make_stop_report(callbacks_in_progress=value)


def test_stop_report_rejects_negative_in_progress() -> None:
    with pytest.raises(ValueError, match="callbacks_in_progress"):
        make_stop_report(callbacks_in_progress=-1)


def test_stop_report_rejects_invalid_latest_error_type() -> None:
    with pytest.raises(TypeError, match="latest_error"):
        make_stop_report(latest_error=object())


def test_stop_report_accepts_sanitized_latest_error() -> None:
    error = make_error()

    assert make_stop_report(latest_error=error).latest_error is error


def test_stop_report_rejects_quiescent_with_callback_in_progress() -> None:
    with pytest.raises(ValueError, match="quiescent"):
        make_stop_report(
            state=TransportState.STOPPING,
            callbacks_quiescent=True,
            callbacks_in_progress=1,
        )


@pytest.mark.parametrize(
    ("callbacks_quiescent", "callbacks_in_progress"),
    ((False, 0), (False, 1)),
)
def test_stopped_report_requires_clean_quiescence(
    callbacks_quiescent: bool,
    callbacks_in_progress: int,
) -> None:
    with pytest.raises(ValueError, match="STOPPED"):
        make_stop_report(
            callbacks_quiescent=callbacks_quiescent,
            callbacks_in_progress=callbacks_in_progress,
        )


def test_stopping_report_may_have_callbacks_in_progress() -> None:
    report = make_stop_report(
        state=TransportState.STOPPING,
        callbacks_quiescent=False,
        callbacks_in_progress=2,
    )

    assert report.callbacks_in_progress == 2


@pytest.mark.parametrize(
    ("callbacks_quiescent", "callbacks_in_progress"),
    ((True, 0), (False, 0), (False, 2)),
)
def test_failed_report_may_be_quiescent_or_not(
    callbacks_quiescent: bool,
    callbacks_in_progress: int,
) -> None:
    report = make_stop_report(
        state=TransportState.FAILED,
        callbacks_quiescent=callbacks_quiescent,
        callbacks_in_progress=callbacks_in_progress,
    )

    assert report.state is TransportState.FAILED


def test_structural_message_sink_has_required_method() -> None:
    class RecordingSink:
        def __init__(self) -> None:
            self.messages: list[MessageEnvelope] = []

        def submit(self, message: MessageEnvelope) -> None:
            self.messages.append(message)

    sink: MessageSink = RecordingSink()

    assert callable(sink.submit)


def test_runtime_service_structurally_conforms_to_message_sink() -> None:
    protocol_signature = inspect.signature(MessageSink.submit)
    runtime_signature = inspect.signature(RuntimeService.submit)

    assert tuple(protocol_signature.parameters) == ("self", "message")
    assert tuple(runtime_signature.parameters) == ("self", "message")
    assert runtime_signature.return_annotation is None


def test_structural_message_transport_has_required_methods() -> None:
    class RecordingTransport:
        def start(self) -> None:
            pass

        def stop(
            self,
            deadline_monotonic: float,
        ) -> TransportStopReport:
            del deadline_monotonic
            return make_stop_report()

        def snapshot(self) -> TransportSnapshot:
            return make_snapshot()

    candidate: MessageTransport = RecordingTransport()

    assert callable(candidate.start)
    assert tuple(inspect.signature(candidate.stop).parameters) == (
        "deadline_monotonic",
    )
    assert callable(candidate.snapshot)


def test_message_transport_protocol_has_approved_method_signatures() -> None:
    assert tuple(inspect.signature(MessageTransport.start).parameters) == (
        "self",
    )
    assert tuple(inspect.signature(MessageTransport.stop).parameters) == (
        "self",
        "deadline_monotonic",
    )
    assert tuple(inspect.signature(MessageTransport.snapshot).parameters) == (
        "self",
    )


def _imported_modules(module: object) -> set[str]:
    source_path = Path(module.__file__)  # type: ignore[attr-defined]
    tree = ast.parse(source_path.read_text(encoding="utf-8"))
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module is not None:
            imported.add(node.module)
    return imported


def test_transport_base_has_no_runtime_import() -> None:
    assert not any(
        name.startswith("shakealert_lab.runtime")
        for name in _imported_modules(transport_base)
    )


def test_transport_base_has_no_native_or_protocol_specific_imports() -> None:
    forbidden_roots = {
        "amqp",
        "http",
        "mqtt",
        "paho",
        "requests",
        "socket",
        "ssl",
        "stomp",
    }

    assert forbidden_roots.isdisjoint(
        name.partition(".")[0]
        for name in _imported_modules(transport_base)
    )


def test_transport_package_exports_exact_approved_api() -> None:
    assert set(transport_package.__all__) == APPROVED_EXPORTS
    assert set(transport_base.__all__) == APPROVED_EXPORTS
    assert all(
        getattr(transport_package, name) is getattr(transport_base, name)
        for name in APPROVED_EXPORTS
    )


def test_public_result_fields_are_confidentiality_safe() -> None:
    assert {field.name for field in fields(SanitizedTransportError)} == {
        "category",
        "exception_type",
        "summary",
        "occurred_at_utc",
    }
    assert {field.name for field in fields(TransportSnapshot)} == {
        "state",
        "connection_state",
        "environment",
        "connection_name",
        "callbacks_received",
        "submissions_accepted",
        "submissions_rejected",
        "queue_saturations",
        "mapping_failures",
        "callbacks_quiescent",
        "latest_error",
    }
    assert {field.name for field in fields(TransportStopReport)} == {
        "state",
        "callbacks_quiescent",
        "callbacks_in_progress",
        "latest_error",
    }

    forbidden_fields = {
        "endpoint",
        "credential",
        "payload",
        "headers",
        "raw_exception",
        "client",
        "socket",
        "tls",
        "username",
        "password",
    }
    public_fields = {
        field.name
        for result_type in (
            SanitizedTransportError,
            TransportSnapshot,
            TransportStopReport,
        )
        for field in fields(result_type)
    }
    assert forbidden_fields.isdisjoint(public_fields)
