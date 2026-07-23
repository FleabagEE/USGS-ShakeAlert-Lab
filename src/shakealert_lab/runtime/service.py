"""Transport-neutral runtime lifecycle coordination."""

from dataclasses import dataclass
from enum import Enum, auto
from queue import Full, Queue
from threading import RLock
from time import monotonic

from shakealert_lab.messaging.inbound import MessageEnvelope
from shakealert_lab.messaging.queue_worker import QueueWorker
from shakealert_lab.messaging.router import TopicRouter, UnknownTopicError
from shakealert_lab.parsing.errors import (
    MessageDecodeError,
    MessageParseError,
    UnsupportedMessageError,
)


class RuntimeState(Enum):
    """Lifecycle states for the transport-neutral runtime."""

    STOPPED = auto()
    STARTING = auto()
    RUNNING = auto()
    STOPPING = auto()
    FAILED = auto()


class SubmissionRejectedError(RuntimeError):
    """Raised when the runtime is not accepting messages."""


class QueueSaturatedError(SubmissionRejectedError):
    """Raised when the bounded runtime queue is full."""


@dataclass(frozen=True, slots=True)
class RuntimeFailure:
    """Sanitized details for the latest unexpected runtime failure."""

    exception_type: str
    message: str


@dataclass(frozen=True, slots=True)
class RuntimeSnapshot:
    """Observable runtime state and counters."""

    state: RuntimeState
    queue_depth: int
    accepted_submissions: int
    rejected_submissions: int
    queue_saturations: int
    processed_messages: int
    message_level_failures: int
    worker_failures: int
    in_progress: int
    latest_failure: RuntimeFailure | None


@dataclass(frozen=True, slots=True)
class ShutdownReport:
    """Result of a deadline-bounded draining shutdown."""

    drained: bool
    remaining_queued: int
    in_progress: int
    state: RuntimeState


class RuntimeService:
    """Coordinate one bounded queue and one routing worker."""

    _MESSAGE_ERROR_TYPES = (
        UnknownTopicError,
        MessageDecodeError,
        MessageParseError,
        UnsupportedMessageError,
    )

    def __init__(
        self,
        router: TopicRouter,
        queue_capacity: int,
        shutdown_deadline_seconds: float,
    ) -> None:
        if queue_capacity <= 0:
            raise ValueError("queue_capacity must be positive")
        if shutdown_deadline_seconds < 0:
            raise ValueError("shutdown_deadline_seconds must be non-negative")

        self._router = router
        self._messages: Queue[MessageEnvelope] = Queue(maxsize=queue_capacity)
        self._shutdown_deadline_seconds = shutdown_deadline_seconds
        self._lock = RLock()
        self._state = RuntimeState.STOPPED
        self._worker: QueueWorker | None = None
        self._accepted_submissions = 0
        self._rejected_submissions = 0
        self._queue_saturations = 0
        self._processed_messages = 0
        self._message_level_failures = 0
        self._worker_failures = 0
        self._in_progress = 0
        self._latest_failure: RuntimeFailure | None = None
        self._shutdown_deadline_monotonic: float | None = None

    @property
    def state(self) -> RuntimeState:
        """Return the current lifecycle state."""
        with self._lock:
            return self._state

    @property
    def worker_ident(self) -> int | None:
        """Return the current worker identifier for observability."""
        with self._lock:
            return None if self._worker is None else self._worker.ident

    def snapshot(self) -> RuntimeSnapshot:
        """Return an atomic view of runtime state and counters."""
        with self._lock:
            return RuntimeSnapshot(
                state=self._state,
                queue_depth=self._messages.qsize(),
                accepted_submissions=self._accepted_submissions,
                rejected_submissions=self._rejected_submissions,
                queue_saturations=self._queue_saturations,
                processed_messages=self._processed_messages,
                message_level_failures=self._message_level_failures,
                worker_failures=self._worker_failures,
                in_progress=self._in_progress,
                latest_failure=self._latest_failure,
            )

    def start(self) -> None:
        """Start one worker, or do nothing when already running."""
        with self._lock:
            if self._state in (RuntimeState.STARTING, RuntimeState.RUNNING):
                return
            if self._state is RuntimeState.FAILED:
                raise RuntimeError("a failed runtime cannot be restarted")
            if self._state is RuntimeState.STOPPING:
                raise RuntimeError("a stopping runtime cannot be started")

            self._state = RuntimeState.STARTING
            worker = QueueWorker(
                messages=self._messages,
                process=self._router.route,
                message_error_types=self._MESSAGE_ERROR_TYPES,
                on_processed=self._record_processed,
                on_message_failure=self._record_message_failure,
                on_worker_failure=self._record_worker_failure,
                on_in_progress=self._set_in_progress,
                on_exit=self._worker_exited,
            )
            self._worker = worker
            try:
                worker.start()
            except BaseException as error:
                self._record_failure(error, "worker startup failed")
                raise
            if self._state is RuntimeState.STARTING:
                self._state = RuntimeState.RUNNING

    def submit(self, message: MessageEnvelope) -> None:
        """Submit without blocking; type violations do not affect counters."""
        if not isinstance(message, MessageEnvelope):
            raise TypeError("message must be a MessageEnvelope")

        with self._lock:
            if self._state is not RuntimeState.RUNNING:
                self._rejected_submissions += 1
                raise SubmissionRejectedError(
                    f"runtime is not accepting messages in {self._state.name}"
                )
            try:
                self._messages.put_nowait(message)
            except Full:
                self._rejected_submissions += 1
                self._queue_saturations += 1
                raise QueueSaturatedError("runtime queue is full") from None
            self._accepted_submissions += 1

    def stop(self) -> ShutdownReport:
        """Stop acceptance and drain accepted work up to the deadline."""
        with self._lock:
            if self._state is RuntimeState.STOPPED:
                return self._shutdown_report()

            worker = self._worker
            if self._state not in (RuntimeState.FAILED, RuntimeState.STOPPING):
                self._state = RuntimeState.STOPPING
                self._shutdown_deadline_monotonic = (
                    monotonic() + self._shutdown_deadline_seconds
                )
            if worker is not None:
                worker.request_stop()
            deadline = self._shutdown_deadline_monotonic

        if worker is not None:
            remaining = (
                0.0 if deadline is None else max(0.0, deadline - monotonic())
            )
            worker.join(remaining)

        with self._lock:
            if (
                worker is not None
                and not worker.is_alive
                and self._state is RuntimeState.STOPPING
            ):
                self._state = RuntimeState.STOPPED
                self._shutdown_deadline_monotonic = None
            return self._shutdown_report()

    def _shutdown_report(self) -> ShutdownReport:
        remaining = self._messages.qsize()
        return ShutdownReport(
            drained=remaining == 0 and self._in_progress == 0,
            remaining_queued=remaining,
            in_progress=self._in_progress,
            state=self._state,
        )

    def _record_processed(self) -> None:
        with self._lock:
            self._processed_messages += 1

    def _record_message_failure(self) -> None:
        with self._lock:
            self._message_level_failures += 1

    def _record_worker_failure(self, error: BaseException) -> None:
        self._record_failure(error, "worker execution failed")

    def _record_failure(self, error: BaseException, message: str) -> None:
        with self._lock:
            self._worker_failures += 1
            self._latest_failure = RuntimeFailure(
                exception_type=type(error).__name__,
                message=message,
            )
            self._state = RuntimeState.FAILED

    def _set_in_progress(self, active: bool) -> None:
        with self._lock:
            self._in_progress = 1 if active else 0

    def _worker_exited(self) -> None:
        with self._lock:
            if self._state is RuntimeState.STOPPING:
                self._state = RuntimeState.STOPPED
                self._shutdown_deadline_monotonic = None
