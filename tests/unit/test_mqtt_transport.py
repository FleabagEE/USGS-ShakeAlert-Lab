"""Deterministic tests for the offline MQTT callback adapter."""

from dataclasses import FrozenInstanceError
from inspect import signature
from math import inf, nan
from threading import Event, Thread
from time import monotonic, sleep
from typing import Callable

import pytest

from shakealert_lab.messaging.inbound import Environment, MessageEnvelope
from shakealert_lab.runtime.service import QueueSaturatedError, SubmissionRejectedError
from shakealert_lab.transport import ConnectionState, TransportErrorCategory, TransportState
from shakealert_lab.transport.mqtt import MQTTTransport, MqttAdapterConfig


class FakeNativeMessage:
    def __init__(self, topic: object, payload: object) -> None:
        self.topic = topic
        self.payload = payload


class FakeMqttClient:
    def __init__(self) -> None:
        self.on_connect: Callable[..., None] | None = None
        self.on_message: Callable[..., None] | None = None

    def deliver(self, topic: object, payload: object) -> None:
        assert self.on_message is not None
        self.on_message(self, None, FakeNativeMessage(topic, payload))


class RecordingSink:
    def __init__(self) -> None:
        self.messages: list[MessageEnvelope] = []

    def submit(self, message: MessageEnvelope) -> None:
        self.messages.append(message)


class RejectingSink:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def submit(self, message: MessageEnvelope) -> None:
        del message
        raise self.error


def wait_until(predicate: Callable[[], bool], timeout: float = 1.0) -> None:
    deadline = monotonic() + timeout
    while not predicate():
        if monotonic() >= deadline:
            pytest.fail("condition was not satisfied before deadline")
        sleep(0.001)


def config(**changes: object) -> MqttAdapterConfig:
    values = dict(
        environment=Environment.SCENARIO,
        connection_name="scenario-mqtt",
        protocol_version="3.1.1",
    )
    values.update(changes)
    return MqttAdapterConfig(**values)  # type: ignore[arg-type]


def adapter(
    sink: object | None = None,
) -> tuple[MQTTTransport, FakeMqttClient, object]:
    client = FakeMqttClient()
    actual_sink = sink or RecordingSink()
    return (
        MQTTTransport(config(), actual_sink, client),  # type: ignore[arg-type]
        client,
        actual_sink,
    )


def test_config_is_frozen_and_slotted() -> None:
    value = config()
    assert not hasattr(value, "__dict__")
    with pytest.raises(FrozenInstanceError):
        value.connection_name = "other"  # type: ignore[misc]


@pytest.mark.parametrize("environment", (Environment.PRODUCTION, Environment.SCENARIO))
def test_config_accepts_operational_environments(environment: Environment) -> None:
    assert config(environment=environment).environment is environment


@pytest.mark.parametrize("environment", (Environment.UNKNOWN,))
def test_config_rejects_unknown_environment(environment: Environment) -> None:
    with pytest.raises(ValueError, match="environment"):
        config(environment=environment)


def test_config_rejects_invalid_environment_type() -> None:
    with pytest.raises(TypeError, match="environment"):
        config(environment="scenario")


@pytest.mark.parametrize("name", ("", " mqtt", "mqtt ", "mqtt\nforged"))
def test_config_rejects_unsanitized_connection_name(name: str) -> None:
    with pytest.raises(ValueError, match="connection_name"):
        config(connection_name=name)


def test_config_rejects_non_string_connection_name() -> None:
    with pytest.raises(TypeError, match="connection_name"):
        config(connection_name=object())


@pytest.mark.parametrize("version", ("3.1", "3.1.1"))
def test_config_accepts_verified_versions(version: str) -> None:
    assert config(protocol_version=version).protocol_version == version


@pytest.mark.parametrize("version", ("", "3.0", "5", " 3.1"))
def test_config_rejects_unverified_versions(version: str) -> None:
    with pytest.raises(ValueError, match="protocol_version"):
        config(protocol_version=version)


def test_config_rejects_non_string_version() -> None:
    with pytest.raises(TypeError, match="protocol_version"):
        config(protocol_version=object())


def test_constructor_rejects_non_config() -> None:
    with pytest.raises(TypeError, match="config"):
        MQTTTransport(object(), RecordingSink(), FakeMqttClient())  # type: ignore[arg-type]


def test_initial_snapshot() -> None:
    transport, _, _ = adapter()
    snapshot = transport.snapshot()
    assert snapshot.state is TransportState.STOPPED
    assert snapshot.connection_state is ConnectionState.UNKNOWN
    assert snapshot.environment is Environment.SCENARIO
    assert snapshot.connection_name == "scenario-mqtt"
    assert snapshot.callbacks_received == 0
    assert snapshot.submissions_accepted == 0
    assert snapshot.submissions_rejected == 0
    assert snapshot.queue_saturations == 0
    assert snapshot.mapping_failures == 0
    assert snapshot.callbacks_quiescent is True
    assert snapshot.latest_error is None


def test_start_registers_only_on_message_and_is_idempotent() -> None:
    transport, client, _ = adapter()
    sentinel = lambda: None
    client.on_connect = sentinel
    transport.start()
    callback = client.on_message
    transport.start()
    assert callback is not None
    assert client.on_message is callback
    assert client.on_connect is sentinel
    assert transport.snapshot().state is TransportState.RUNNING


def test_callback_preserves_payload_and_maps_exact_topic() -> None:
    transport, client, sink = adapter()
    assert isinstance(sink, RecordingSink)
    payload = b"\x00<event>\xff"
    transport.start()
    client.deliver("eew/CaseSensitive", payload)
    envelope = sink.messages[0]
    assert envelope.payload is payload
    assert envelope.destination == "eew/CaseSensitive"
    assert envelope.environment is Environment.SCENARIO
    assert envelope.connection_name == "scenario-mqtt"
    assert envelope.protocol == "mqtt"
    assert envelope.protocol_version == "3.1.1"
    assert envelope.verified_metadata == {}
    assert envelope.received_at_utc.utcoffset().total_seconds() == 0
    snapshot = transport.snapshot()
    assert snapshot.callbacks_received == 1
    assert snapshot.submissions_accepted == 1


def test_callback_before_start_is_lifecycle_rejection() -> None:
    transport, _, sink = adapter()
    assert isinstance(sink, RecordingSink)
    transport._on_message(object(), None, FakeNativeMessage("topic", b"data"))
    assert sink.messages == []
    snapshot = transport.snapshot()
    assert snapshot.callbacks_received == 1
    assert snapshot.submissions_rejected == 1
    assert snapshot.latest_error is None


@pytest.mark.parametrize(
    ("error", "saturations"),
    ((SubmissionRejectedError("stopped"), 0), (QueueSaturatedError("full"), 1)),
)
def test_runtime_rejections_are_observations_only(
    error: Exception, saturations: int
) -> None:
    transport, client, _ = adapter(RejectingSink(error))
    transport.start()
    client.deliver("topic", b"data")
    snapshot = transport.snapshot()
    assert snapshot.submissions_rejected == 1
    assert snapshot.queue_saturations == saturations
    assert snapshot.latest_error is None
    assert snapshot.state is TransportState.RUNNING


@pytest.mark.parametrize(
    ("topic", "payload"), ((object(), b"data"), ("topic", bytearray(b"data")))
)
def test_mapping_failure_is_sanitized(topic: object, payload: object) -> None:
    transport, client, _ = adapter()
    transport.start()
    client.deliver(topic, payload)
    snapshot = transport.snapshot()
    assert snapshot.mapping_failures == 1
    assert snapshot.submissions_accepted == 0
    assert snapshot.latest_error is not None
    assert snapshot.latest_error.category is TransportErrorCategory.CALLBACK_MAPPING
    assert snapshot.latest_error.summary == "MQTT callback mapping failed"


def test_unexpected_sink_failure_is_contained_and_latched() -> None:
    secret = "secret-native-text"
    transport, client, _ = adapter(RejectingSink(ValueError(secret)))
    transport.start()
    client.deliver("topic", b"sensitive")
    snapshot = transport.snapshot()
    assert snapshot.state is TransportState.FAILED
    assert snapshot.latest_error is not None
    assert snapshot.latest_error.category is TransportErrorCategory.CLIENT_CALLBACK
    assert snapshot.latest_error.exception_type == "ValueError"
    assert secret not in snapshot.latest_error.summary
    with pytest.raises(RuntimeError, match="cannot be restarted"):
        transport.start()


class RegistrationFailingClient:
    on_connect: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return None

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        del value
        raise ValueError("credential endpoint raw detail")


def test_registration_failure_is_sanitized() -> None:
    transport = MQTTTransport(config(), RecordingSink(), RegistrationFailingClient())
    with pytest.raises(RuntimeError, match="callback registration") as raised:
        transport.start()
    assert raised.value.__cause__ is None
    snapshot = transport.snapshot()
    assert snapshot.state is TransportState.FAILED
    assert snapshot.latest_error is not None
    assert snapshot.latest_error.category is TransportErrorCategory.STARTUP
    assert snapshot.latest_error.exception_type == "ValueError"
    assert "credential" not in snapshot.latest_error.summary
    assert "endpoint" not in snapshot.latest_error.summary


@pytest.mark.parametrize("deadline", (True, "1", None, object()))
def test_stop_rejects_invalid_deadline_type(deadline: object) -> None:
    transport, _, _ = adapter()
    with pytest.raises(TypeError, match="deadline_monotonic"):
        transport.stop(deadline)  # type: ignore[arg-type]


@pytest.mark.parametrize("deadline", (nan, inf, -inf))
def test_stop_rejects_nonfinite_deadline(deadline: float) -> None:
    transport, _, _ = adapter()
    with pytest.raises(ValueError, match="finite"):
        transport.stop(deadline)


def test_stop_before_start_is_clean() -> None:
    transport, client, _ = adapter()
    report = transport.stop(monotonic())
    assert report.state is TransportState.STOPPED
    assert report.callbacks_quiescent is True
    assert report.callbacks_in_progress == 0
    assert client.on_message is None


def test_stop_after_start_detaches_closes_gate_and_is_conservative() -> None:
    transport, client, sink = adapter()
    assert isinstance(sink, RecordingSink)
    transport.start()
    callback = client.on_message
    assert callback is not None
    report = transport.stop(monotonic())
    callback(client, None, FakeNativeMessage("late", b"data"))
    wait_until(lambda: client.on_message is None)
    assert report.state is TransportState.STOPPING
    assert report.callbacks_quiescent is False
    assert sink.messages == []
    assert transport.snapshot().submissions_rejected == 1


def test_repeated_stop_never_extends_deadline() -> None:
    transport, _, _ = adapter()
    transport.start()
    transport.stop(10.0)
    transport.stop(20.0)
    assert transport._stop_deadline_monotonic == 10.0
    transport.stop(5.0)
    assert transport._stop_deadline_monotonic == 5.0


def test_snapshot_allows_incomplete_callback_accounting() -> None:
    entered = Event()
    release = Event()

    class BlockingSink:
        def submit(self, message: MessageEnvelope) -> None:
            del message
            entered.set()
            assert release.wait(2.0)

    transport, client, _ = adapter(BlockingSink())
    transport.start()
    worker = Thread(target=client.deliver, args=("topic", b"data"))
    worker.start()
    assert entered.wait(2.0)
    snapshot = transport.snapshot()
    assert snapshot.callbacks_received == 1
    assert snapshot.submissions_accepted == 0
    assert snapshot.callbacks_quiescent is False
    release.set()
    worker.join(2.0)
    assert not worker.is_alive()
    assert transport.snapshot().submissions_accepted == 1


class BlockingRegistrationClient:
    def __init__(self) -> None:
        self.on_connect: Callable[..., None] | None = None
        self.registration_entered = Event()
        self.release_registration = Event()
        self.detachment_count = 0
        self._on_message: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return self._on_message

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        if value is None:
            self.detachment_count += 1
            self._on_message = None
            return
        self.registration_entered.set()
        assert self.release_registration.wait(2.0)
        self._on_message = value


def test_second_start_returns_immediately_while_registration_blocks() -> None:
    client = BlockingRegistrationClient()
    transport = MQTTTransport(config(), RecordingSink(), client)
    first_start = Thread(target=transport.start)
    first_start.start()
    assert client.registration_entered.wait(1.0)
    assert transport.snapshot().state is TransportState.STARTING

    started_at = monotonic()
    transport.start()
    elapsed = monotonic() - started_at

    assert elapsed < 0.1
    assert transport.snapshot().state is TransportState.STARTING
    client.release_registration.set()
    first_start.join(1.0)
    assert not first_start.is_alive()
    assert transport.snapshot().state is TransportState.RUNNING


def test_stop_during_registration_returns_and_defers_one_detachment() -> None:
    client = BlockingRegistrationClient()
    transport = MQTTTransport(config(), RecordingSink(), client)
    first_start = Thread(target=transport.start)
    first_start.start()
    assert client.registration_entered.wait(1.0)

    started_at = monotonic()
    report = transport.stop(monotonic() - 1.0)
    elapsed = monotonic() - started_at

    assert elapsed < 0.1
    assert report.state is TransportState.STOPPING
    assert report.callbacks_quiescent is False
    assert client.detachment_count == 0

    client.release_registration.set()
    first_start.join(1.0)
    assert not first_start.is_alive()
    wait_until(lambda: client.detachment_count == 1)
    assert client.on_message is None
    transport.stop(monotonic())
    sleep(0.01)
    assert client.detachment_count == 1


class SynchronousCallbackClient:
    def __init__(self) -> None:
        self.on_connect: Callable[..., None] | None = None
        self._on_message: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return self._on_message

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        if value is not None:
            value(self, None, FakeNativeMessage("sync/topic", b"sync"))
        self._on_message = value


def test_native_setter_may_invoke_callback_synchronously() -> None:
    client = SynchronousCallbackClient()
    sink = RecordingSink()
    transport = MQTTTransport(config(), sink, client)

    transport.start()

    snapshot = transport.snapshot()
    assert snapshot.state is TransportState.RUNNING
    assert snapshot.callbacks_received == 1
    assert snapshot.submissions_rejected == 1
    assert sink.messages == []


class BlockingDetachmentClient(FakeMqttClient):
    def __init__(self) -> None:
        self.on_connect = None
        self.detachment_entered = Event()
        self.release_detachment = Event()
        self.detachment_count = 0
        self._on_message: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return self._on_message

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        if value is None:
            self.detachment_count += 1
            self.detachment_entered.set()
            assert self.release_detachment.wait(2.0)
        self._on_message = value


def test_blocked_detachment_does_not_delay_expired_stop() -> None:
    client = BlockingDetachmentClient()
    transport = MQTTTransport(config(), RecordingSink(), client)
    transport.start()

    started_at = monotonic()
    report = transport.stop(monotonic() - 1.0)
    elapsed = monotonic() - started_at

    assert elapsed < 0.1
    assert report.state is TransportState.STOPPING
    assert report.callbacks_quiescent is False
    assert client.detachment_entered.wait(1.0)
    client.release_detachment.set()
    wait_until(lambda: client.on_message is None)


class DetachmentFailingClient(FakeMqttClient):
    def __init__(self, error: BaseException) -> None:
        self.on_connect = None
        self.error = error
        self._on_message: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return self._on_message

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        if value is None:
            raise self.error
        self._on_message = value


def test_ordinary_detachment_failure_is_sanitized_and_latched() -> None:
    client = DetachmentFailingClient(
        ValueError("credential endpoint native detail")
    )
    transport = MQTTTransport(config(), RecordingSink(), client)
    transport.start()
    transport.stop(monotonic())

    wait_until(lambda: transport.snapshot().state is TransportState.FAILED)
    snapshot = transport.snapshot()
    assert snapshot.latest_error is not None
    assert snapshot.latest_error.category is TransportErrorCategory.SHUTDOWN
    assert snapshot.latest_error.exception_type == "ValueError"
    assert "credential" not in snapshot.latest_error.summary
    assert "endpoint" not in snapshot.latest_error.summary


class FatalDetachment(BaseException):
    pass


def test_detachment_does_not_swallow_base_exception(monkeypatch: pytest.MonkeyPatch) -> None:
    import shakealert_lab.transport.mqtt as mqtt_module

    fatal = FatalDetachment("fatal native detail")
    client = DetachmentFailingClient(fatal)
    transport = MQTTTransport(config(), RecordingSink(), client)
    transport.start()

    class InlineThread:
        def __init__(self, *, target: Callable[[], None], **kwargs: object) -> None:
            del kwargs
            self._target = target

        def start(self) -> None:
            self._target()

    monkeypatch.setattr(mqtt_module, "Thread", InlineThread)
    with pytest.raises(FatalDetachment) as raised:
        transport.stop(monotonic())
    assert raised.value is fatal


def test_clean_stop_then_start_uses_independent_deadline() -> None:
    transport, _, _ = adapter()
    clean = transport.stop(-10**1000)
    assert clean.state is TransportState.STOPPED
    assert transport._stop_deadline_monotonic is None

    transport.start()
    new_deadline = 10**1000
    transport.stop(new_deadline)
    assert transport._stop_deadline_monotonic == new_deadline


@pytest.mark.parametrize("deadline", (10**1000, -(10**1000)))
def test_stop_accepts_arbitrarily_large_integer_deadline(deadline: int) -> None:
    transport, _, _ = adapter()
    transport.start()
    report = transport.stop(deadline)
    assert report.state is TransportState.STOPPING
    assert transport._stop_deadline_monotonic == deadline


def test_public_method_signatures_match_transport_contract() -> None:
    assert list(signature(MQTTTransport.start).parameters) == ["self"]
    assert list(signature(MQTTTransport.stop).parameters) == [
        "self",
        "deadline_monotonic",
    ]
    assert list(signature(MQTTTransport.snapshot).parameters) == ["self"]


class CountingDetachmentClient(FakeMqttClient):
    def __init__(self) -> None:
        self.on_connect = None
        self.detachment_count = 0
        self._on_message: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return self._on_message

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        if value is None:
            self.detachment_count += 1
        self._on_message = value


def test_repeated_stop_launches_only_one_detachment() -> None:
    client = CountingDetachmentClient()
    transport = MQTTTransport(config(), RecordingSink(), client)
    transport.start()

    for deadline in (20.0, 30.0, 10.0, 5.0):
        transport.stop(deadline)

    wait_until(lambda: client.detachment_count == 1)
    sleep(0.01)
    assert client.detachment_count == 1
    assert transport._stop_deadline_monotonic == 5.0


def test_concurrent_callback_counters_preserve_invariants() -> None:
    transport, client, sink = adapter()
    assert isinstance(sink, RecordingSink)
    transport.start()
    count = 24
    workers = [
        Thread(target=client.deliver, args=(f"topic/{index}", b"data"))
        for index in range(count)
    ]

    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join(1.0)
        assert not worker.is_alive()

    snapshot = transport.snapshot()
    accounted = (
        snapshot.submissions_accepted
        + snapshot.submissions_rejected
        + snapshot.mapping_failures
    )
    assert snapshot.callbacks_received == count
    assert snapshot.submissions_accepted == count
    assert accounted <= snapshot.callbacks_received
    assert snapshot.queue_saturations <= snapshot.submissions_rejected


def test_captured_callback_does_not_submit_after_stop_closes_acceptance() -> None:
    transport, client, sink = adapter()
    assert isinstance(sink, RecordingSink)
    transport.start()
    callback = client.on_message
    assert callback is not None

    transport.stop(monotonic())
    callback(client, None, FakeNativeMessage("late/topic", b"late"))

    assert sink.messages == []
    snapshot = transport.snapshot()
    assert snapshot.submissions_rejected == 1



def test_start_rejects_while_stopping() -> None:
    client = BlockingDetachmentClient()
    transport = MQTTTransport(config(), RecordingSink(), client)
    transport.start()
    report = transport.stop(monotonic())
    assert report.state is TransportState.STOPPING
    assert client.detachment_entered.wait(1.0)

    try:
        with pytest.raises(RuntimeError, match="stopping transport"):
            transport.start()
        assert transport.snapshot().state is TransportState.STOPPING
        assert client.detachment_count == 1
    finally:
        client.release_detachment.set()



def test_stop_reports_callback_in_progress_without_claiming_quiescence() -> None:
    callback_entered = Event()
    release_callback = Event()

    class BlockingCallbackSink:
        def submit(self, message: MessageEnvelope) -> None:
            del message
            callback_entered.set()
            assert release_callback.wait(2.0)

    transport, client, _ = adapter(BlockingCallbackSink())
    transport.start()
    callback_worker = Thread(
        target=client.deliver,
        args=("active/topic", b"data"),
    )
    callback_worker.start()
    assert callback_entered.wait(1.0)

    try:
        report = transport.stop(monotonic())
        assert report.state is TransportState.STOPPING
        assert report.callbacks_quiescent is False
        assert report.callbacks_in_progress == 1
    finally:
        release_callback.set()
        callback_worker.join(1.0)

    assert not callback_worker.is_alive()



def test_concurrent_stop_calls_preserve_earliest_deadline_and_one_worker() -> None:
    client = CountingDetachmentClient()
    transport = MQTTTransport(config(), RecordingSink(), client)
    transport.start()
    release_stops = Event()
    deadlines = [40.0, 5.0, 30.0, -10.0, 20.0, 0.0]
    reports: list[object] = []
    errors: list[BaseException] = []

    def stop_transport(deadline: float) -> None:
        try:
            assert release_stops.wait(1.0)
            reports.append(transport.stop(deadline))
        except BaseException as error:
            errors.append(error)

    workers = [
        Thread(target=stop_transport, args=(deadline,))
        for deadline in deadlines
    ]
    for worker in workers:
        worker.start()
    release_stops.set()
    for worker in workers:
        worker.join(1.0)
        assert not worker.is_alive()

    wait_until(lambda: client.detachment_count == 1)
    assert errors == []
    assert len(reports) == len(deadlines)
    assert all(
        report.state is TransportState.STOPPING
        and report.callbacks_quiescent is False
        for report in reports
    )
    assert transport._stop_deadline_monotonic == min(deadlines)
    assert client.detachment_count == 1



class FatalRegistration(BaseException):
    pass


class FatalRegistrationClient:
    def __init__(self, error: BaseException) -> None:
        self.on_connect: Callable[..., None] | None = None
        self.error = error
        self.detachment_count = 0
        self._on_message: Callable[..., None] | None = None

    @property
    def on_message(self) -> Callable[..., None] | None:
        return self._on_message

    @on_message.setter
    def on_message(self, value: Callable[..., None] | None) -> None:
        if value is None:
            self.detachment_count += 1
            self._on_message = None
            return
        raise self.error


def test_registration_base_exception_propagates_without_leaving_false_readiness() -> None:
    fatal = FatalRegistration("fatal native registration detail")
    client = FatalRegistrationClient(fatal)
    transport = MQTTTransport(config(), RecordingSink(), client)

    with pytest.raises(FatalRegistration) as raised:
        transport.start()

    assert raised.value is fatal
    snapshot = transport.snapshot()
    assert snapshot.state is TransportState.FAILED
    assert snapshot.callbacks_quiescent is True
    with pytest.raises(RuntimeError, match="cannot be restarted"):
        transport.start()

    report = transport.stop(monotonic())
    assert report.state is TransportState.FAILED
    wait_until(lambda: client.detachment_count == 1)
