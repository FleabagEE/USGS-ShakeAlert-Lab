"""Tests for the transport-neutral runtime skeleton."""

import ast
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from threading import Barrier, Event, Thread
from time import monotonic, sleep
from typing import Protocol

import pytest

import shakealert_lab.messaging.queue_worker as queue_worker_module
import shakealert_lab.runtime.service as runtime_service_module
from shakealert_lab.messaging.inbound import MessageEnvelope
from shakealert_lab.messaging.router import TopicRouter
from shakealert_lab.parsing.errors import MessageParseError
from shakealert_lab.runtime.service import (
    QueueSaturatedError,
    RuntimeService,
    RuntimeState,
    SubmissionRejectedError,
)


class Handler(Protocol):
    def handle(self, message: MessageEnvelope) -> None: ...


def message(
    payload: bytes = b"message",
    topic: str = "eew.sys.dm.data",
) -> MessageEnvelope:
    return MessageEnvelope(
        topic=topic,
        payload=payload,
        received_at_utc=datetime.now(timezone.utc),
        qos=0,
        retain=False,
    )


def wait_until(predicate: Callable[[], bool], timeout: float = 1.0) -> None:
    deadline = monotonic() + timeout
    while not predicate():
        if monotonic() >= deadline:
            raise AssertionError("condition was not met before timeout")
        sleep(0.001)


class RecordingHandler:
    def __init__(self) -> None:
        self.payloads: list[bytes] = []

    def handle(self, envelope: MessageEnvelope) -> None:
        self.payloads.append(envelope.payload)


def service_for(
    handler: Handler,
    *,
    capacity: int = 4,
    deadline: float = 1.0,
) -> RuntimeService:
    return RuntimeService(
        TopicRouter({"eew.sys.dm.data": handler}),
        queue_capacity=capacity,
        shutdown_deadline_seconds=deadline,
    )


def test_initial_state_is_stopped_and_no_processing_occurs() -> None:
    runtime = service_for(RecordingHandler())

    assert runtime.state is RuntimeState.STOPPED
    with pytest.raises(SubmissionRejectedError):
        runtime.submit(message())
    snapshot = runtime.snapshot()
    assert snapshot.processed_messages == 0
    assert snapshot.rejected_submissions == 1
    assert snapshot.queue_saturations == 0


def test_start_transitions_to_running() -> None:
    runtime = service_for(RecordingHandler())

    runtime.start()

    assert runtime.state is RuntimeState.RUNNING
    runtime.stop()


def test_repeated_start_keeps_the_same_worker() -> None:
    runtime = service_for(RecordingHandler())
    runtime.start()
    worker_ident = runtime.worker_ident

    runtime.start()

    assert runtime.worker_ident == worker_ident
    runtime.stop()


def test_worker_processes_messages_in_fifo_order() -> None:
    handler = RecordingHandler()
    runtime = service_for(handler)
    runtime.start()

    for value in (b"first", b"second", b"third"):
        runtime.submit(message(value))

    report = runtime.stop()

    assert report.drained
    assert handler.payloads == [b"first", b"second", b"third"]
    assert runtime.snapshot().processed_messages == 3


def test_queue_capacity_is_enforced_with_explicit_rejection() -> None:
    entered = Event()
    release = Event()

    class BlockingHandler:
        def handle(self, envelope: MessageEnvelope) -> None:
            del envelope
            entered.set()
            release.wait()

    runtime = service_for(BlockingHandler(), capacity=1)
    runtime.start()
    runtime.submit(message(b"in progress"))
    assert entered.wait(1.0)
    runtime.submit(message(b"queued"))

    with pytest.raises(QueueSaturatedError):
        runtime.submit(message(b"rejected"))

    snapshot = runtime.snapshot()
    assert snapshot.queue_depth == 1
    assert snapshot.accepted_submissions == 2
    assert snapshot.rejected_submissions == 1
    assert snapshot.queue_saturations == 1
    release.set()
    assert runtime.stop().drained


def test_message_level_failure_is_isolated() -> None:
    class ParsingHandler:
        def handle(self, envelope: MessageEnvelope) -> None:
            if envelope.payload == b"bad":
                raise MessageParseError("expected test failure")

    runtime = service_for(ParsingHandler())
    runtime.start()
    runtime.submit(message(b"bad"))
    runtime.submit(message(b"good"))

    runtime.stop()

    snapshot = runtime.snapshot()
    assert snapshot.state is RuntimeState.STOPPED
    assert snapshot.message_level_failures == 1
    assert snapshot.processed_messages == 1
    assert snapshot.worker_failures == 0


def test_unexpected_worker_failure_transitions_to_failed_and_rejects() -> None:
    class BrokenHandler:
        def handle(self, envelope: MessageEnvelope) -> None:
            del envelope
            raise AssertionError("programming defect with confidential payload")

    runtime = service_for(BrokenHandler())
    runtime.start()
    runtime.submit(message())
    wait_until(lambda: runtime.state is RuntimeState.FAILED)

    with pytest.raises(SubmissionRejectedError):
        runtime.submit(message(b"after failure"))

    snapshot = runtime.snapshot()
    assert snapshot.worker_failures == 1
    assert snapshot.rejected_submissions == 1
    assert snapshot.latest_failure is not None
    assert snapshot.latest_failure.exception_type == "AssertionError"
    assert snapshot.latest_failure.message == "worker execution failed"
    assert "confidential" not in snapshot.latest_failure.message
    assert runtime.stop().state is RuntimeState.FAILED


def test_stop_drains_accepted_work_and_is_idempotent() -> None:
    handler = RecordingHandler()
    runtime = service_for(handler)
    runtime.start()
    runtime.submit(message(b"accepted"))

    first = runtime.stop()
    second = runtime.stop()

    assert first.drained
    assert second.drained
    assert first.state is RuntimeState.STOPPED
    assert second.state is RuntimeState.STOPPED
    assert handler.payloads == [b"accepted"]


def test_forced_shutdown_reports_in_progress_and_queued_work() -> None:
    entered = Event()
    release = Event()

    class BlockingHandler:
        def handle(self, envelope: MessageEnvelope) -> None:
            del envelope
            entered.set()
            release.wait()

    runtime = service_for(BlockingHandler(), capacity=1, deadline=0.0)
    runtime.start()
    runtime.submit(message(b"in progress"))
    assert entered.wait(1.0)
    runtime.submit(message(b"queued"))

    report = runtime.stop()

    assert not report.drained
    assert report.in_progress == 1
    assert report.remaining_queued == 1
    assert report.state is RuntimeState.STOPPING
    release.set()
    wait_until(lambda: runtime.state is RuntimeState.STOPPED)


def test_submit_requires_message_envelope() -> None:
    runtime = service_for(RecordingHandler())
    runtime.start()

    with pytest.raises(TypeError, match="MessageEnvelope"):
        runtime.submit(object())  # type: ignore[arg-type]

    snapshot = runtime.snapshot()
    assert snapshot.rejected_submissions == 0
    assert snapshot.queue_saturations == 0
    runtime.stop()


def test_startup_failure_is_sanitized_and_latches_failed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = service_for(RecordingHandler())

    def fail_start(worker: object) -> None:
        del worker
        raise OSError("password=confidential")

    monkeypatch.setattr(runtime_service_module.QueueWorker, "start", fail_start)

    with pytest.raises(OSError):
        runtime.start()

    snapshot = runtime.snapshot()
    assert snapshot.state is RuntimeState.FAILED
    assert snapshot.worker_failures == 1
    assert snapshot.latest_failure is not None
    assert snapshot.latest_failure.exception_type == "OSError"
    assert snapshot.latest_failure.message == "worker startup failed"
    assert "password" not in snapshot.latest_failure.message


def test_repeated_stop_uses_one_absolute_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    entered = Event()
    release = Event()

    class BlockingHandler:
        def handle(self, envelope: MessageEnvelope) -> None:
            del envelope
            entered.set()
            release.wait()

    clock = [100.0]
    monkeypatch.setattr(runtime_service_module, "monotonic", lambda: clock[0])
    runtime = service_for(BlockingHandler(), deadline=10.0)
    runtime.start()
    runtime.submit(message())
    assert entered.wait(1.0)
    worker = runtime._worker
    assert worker is not None
    original_join = worker.join
    join_timeouts: list[float] = []
    monkeypatch.setattr(worker, "join", join_timeouts.append)

    runtime.stop()
    clock[0] = 104.0
    runtime.stop()

    assert join_timeouts == pytest.approx([10.0, 6.0])
    release.set()
    original_join(1.0)
    wait_until(lambda: runtime.state is RuntimeState.STOPPED)


def test_concurrent_start_calls_create_one_running_worker() -> None:
    runtime = service_for(RecordingHandler())
    barrier = Barrier(3)
    errors: list[BaseException] = []

    def start_runtime() -> None:
        barrier.wait()
        try:
            runtime.start()
        except BaseException as error:
            errors.append(error)

    callers = [Thread(target=start_runtime) for _ in range(2)]
    for caller in callers:
        caller.start()
    barrier.wait()
    for caller in callers:
        caller.join(1.0)

    assert errors == []
    assert all(not caller.is_alive() for caller in callers)
    assert runtime.state is RuntimeState.RUNNING
    assert runtime.worker_ident is not None
    runtime.stop()


def test_unknown_topic_failure_is_isolated_and_processing_continues() -> None:
    handler = RecordingHandler()
    runtime = service_for(handler)
    runtime.start()
    runtime.submit(message(b"unknown", topic="eew.sys.ha.data"))
    runtime.submit(message(b"known"))

    runtime.stop()

    snapshot = runtime.snapshot()
    assert snapshot.message_level_failures == 1
    assert snapshot.processed_messages == 1
    assert snapshot.worker_failures == 0
    assert handler.payloads == [b"known"]


def test_queued_work_remains_observable_after_fatal_worker_failure() -> None:
    entered = Event()
    release = Event()

    class FailingHandler:
        def handle(self, envelope: MessageEnvelope) -> None:
            del envelope
            entered.set()
            release.wait()
            raise AssertionError("fatal")

    runtime = service_for(FailingHandler(), capacity=1)
    runtime.start()
    runtime.submit(message(b"fatal"))
    assert entered.wait(1.0)
    runtime.submit(message(b"still queued"))
    release.set()
    wait_until(lambda: runtime.state is RuntimeState.FAILED)

    snapshot = runtime.snapshot()
    assert snapshot.queue_depth == 1
    assert snapshot.in_progress == 0
    assert snapshot.worker_failures == 1
    report = runtime.stop()
    assert report.remaining_queued == 1
    assert report.state is RuntimeState.FAILED


def test_on_exit_failure_is_reported_without_replacing_itself() -> None:
    runtime = service_for(RecordingHandler())

    def fail_on_exit() -> None:
        raise RuntimeError("exit callback failure")

    runtime._worker_exited = fail_on_exit  # type: ignore[method-assign]
    runtime.start()

    report = runtime.stop()

    snapshot = runtime.snapshot()
    assert report.state is RuntimeState.FAILED
    assert snapshot.worker_failures == 1
    assert snapshot.latest_failure is not None
    assert snapshot.latest_failure.exception_type == "RuntimeError"
    assert snapshot.latest_failure.message == "worker execution failed"


def test_runtime_has_no_transport_storage_or_parser_dependencies() -> None:
    imported_modules: set[str] = set()
    for module in (queue_worker_module, runtime_service_module):
        source_path = Path(module.__file__)  # type: ignore[arg-type]
        tree = ast.parse(source_path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported_modules.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module is not None:
                imported_modules.add(node.module)

    forbidden_roots = {
        "amqp",
        "http",
        "paho",
        "requests",
        "socket",
        "ssl",
        "stomp",
    }
    assert forbidden_roots.isdisjoint(
        name.partition(".")[0] for name in imported_modules
    )
    assert not any(
        name.startswith(
            (
                "shakealert_lab.transport",
                "shakealert_lab.storage",
                "shakealert_lab.parsing.event",
                "shakealert_lab.parsing.health",
            )
        )
        for name in imported_modules
    )
