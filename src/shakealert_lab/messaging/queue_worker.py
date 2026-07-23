"""Single-worker FIFO message processing."""

from collections.abc import Callable
from queue import Empty, Queue
from threading import Event, Thread

from shakealert_lab.messaging.inbound import MessageEnvelope


class WorkerReadinessError(RuntimeError):
    """Raised when a worker does not become ready before its deadline."""


class QueueWorker:
    """Consume queued envelopes with one worker thread."""

    def __init__(
        self,
        messages: Queue[MessageEnvelope],
        process: Callable[[MessageEnvelope], None],
        message_error_types: tuple[type[Exception], ...],
        on_processed: Callable[[], None],
        on_message_failure: Callable[[], None],
        on_worker_failure: Callable[[BaseException], None],
        on_in_progress: Callable[[bool], None],
        on_exit: Callable[[], None],
        readiness_timeout_seconds: float = 1.0,
    ) -> None:
        if readiness_timeout_seconds <= 0:
            raise ValueError("readiness_timeout_seconds must be positive")

        self._messages = messages
        self._process = process
        self._message_error_types = message_error_types
        self._on_processed = on_processed
        self._on_message_failure = on_message_failure
        self._on_worker_failure = on_worker_failure
        self._on_in_progress = on_in_progress
        self._on_exit = on_exit
        self._readiness_timeout_seconds = readiness_timeout_seconds
        self._stop_requested = Event()
        self._ready = Event()
        self._reported_failure: BaseException | None = None
        self._thread = Thread(
            target=self._run,
            name="shakealert-message-worker",
            daemon=True,
        )

    @property
    def ident(self) -> int | None:
        """Return the worker thread identifier."""
        return self._thread.ident

    @property
    def is_alive(self) -> bool:
        """Return whether the worker thread is alive."""
        return self._thread.is_alive()

    def start(self) -> None:
        """Start the worker and wait until it is ready."""
        self._thread.start()
        if not self._ready.wait(self._readiness_timeout_seconds):
            self._stop_requested.set()
            raise WorkerReadinessError("worker readiness deadline expired")

    def request_stop(self) -> None:
        """Ask the worker to exit after draining accepted work."""
        self._stop_requested.set()

    def join(self, timeout: float) -> None:
        """Wait up to timeout seconds for the worker to exit."""
        self._thread.join(timeout)

    def _run(self) -> None:
        self._ready.set()
        try:
            while not (self._stop_requested.is_set() and self._messages.empty()):
                try:
                    message = self._messages.get(timeout=0.01)
                except Empty:
                    continue

                self._on_in_progress(True)
                try:
                    self._process(message)
                except self._message_error_types:
                    self._on_message_failure()
                except BaseException as error:
                    self._report_failure(error)
                    return
                else:
                    self._on_processed()
                finally:
                    self._on_in_progress(False)
                    self._messages.task_done()
        except BaseException as error:
            self._report_failure(error)
        finally:
            try:
                self._on_exit()
            except BaseException as error:
                self._report_failure(error)

    def _report_failure(self, error: BaseException) -> None:
        """Report only the first unexpected worker failure."""
        if self._reported_failure is not None:
            return
        self._reported_failure = error
        try:
            self._on_worker_failure(error)
        except BaseException:
            # The original failure remains available and is not recursively
            # replaced by a failure in its notification callback.
            return
