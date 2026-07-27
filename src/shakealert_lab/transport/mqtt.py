"""Offline MQTT callback adapter.

This module intentionally contains no MQTT client-library import or network
behavior. A native client is injected and used only for callback registration.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from math import isfinite
from threading import RLock, Thread
from typing import Callable, Protocol

from shakealert_lab.messaging.inbound import Environment, MessageEnvelope
from shakealert_lab.runtime.service import (
    QueueSaturatedError,
    SubmissionRejectedError,
)
from shakealert_lab.transport.base import (
    ConnectionState,
    MessageSink,
    SanitizedTransportError,
    TransportErrorCategory,
    TransportSnapshot,
    TransportState,
    TransportStopReport,
)


__all__ = ["MQTTTransport", "MqttAdapterConfig"]


_ConnectCallback = Callable[..., None]
_MessageCallback = Callable[[object, object, "_NativeMqttMessage"], None]


class _NativeMqttMessage(Protocol):
    """Minimum verified native message surface used by this adapter."""

    topic: str
    payload: bytes


class _MqttClient(Protocol):
    """Private callback-registration surface of an injected MQTT client."""

    on_connect: _ConnectCallback | None
    on_message: _MessageCallback | None


def _contains_control_characters(value: str) -> bool:
    return any(
        ord(character) < 32 or ord(character) == 127
        for character in value
    )


def _validate_connection_name(value: object) -> None:
    if not isinstance(value, str):
        raise TypeError("connection_name must be a string")
    if (
        not value
        or value != value.strip()
        or _contains_control_characters(value)
    ):
        raise ValueError(
            "connection_name must be non-empty, trimmed, and contain no "
            "control characters"
        )


def _sanitized_exception_type(error: Exception) -> str:
    name = type(error).__name__
    if (
        not name
        or len(name) > 128
        or not name.isidentifier()
        or _contains_control_characters(name)
    ):
        return "Exception"
    return name


@dataclass(frozen=True, slots=True)
class MqttAdapterConfig:
    """Immutable verified configuration for the offline MQTT adapter."""

    environment: Environment
    connection_name: str
    protocol_version: str

    def __post_init__(self) -> None:
        if not isinstance(self.environment, Environment):
            raise TypeError("environment must be an Environment")
        if self.environment not in (
            Environment.PRODUCTION,
            Environment.SCENARIO,
        ):
            raise ValueError("environment must be PRODUCTION or SCENARIO")

        _validate_connection_name(self.connection_name)

        if not isinstance(self.protocol_version, str):
            raise TypeError("protocol_version must be a string")
        if self.protocol_version not in ("3.1", "3.1.1"):
            raise ValueError("protocol_version must be 3.1 or 3.1.1")


class MQTTTransport:
    """Protocol-neutral handoff for injected MQTT message callbacks."""

    def __init__(
        self,
        config: MqttAdapterConfig,
        sink: MessageSink,
        client: _MqttClient,
    ) -> None:
        if not isinstance(config, MqttAdapterConfig):
            raise TypeError("config must be a MqttAdapterConfig")

        self._config = config
        self._sink = sink
        self._client = client
        self._lock = RLock()
        self._state = TransportState.STOPPED
        self._connection_state = ConnectionState.UNKNOWN
        self._accepting_callbacks = False
        self._callback_was_registered = False
        self._callbacks_received = 0
        self._submissions_accepted = 0
        self._submissions_rejected = 0
        self._queue_saturations = 0
        self._mapping_failures = 0
        self._callbacks_in_progress = 0
        self._latest_error: SanitizedTransportError | None = None
        self._stop_deadline_monotonic: int | float | None = None
        self._registration_in_progress = False
        self._detachment_requested = False
        self._detachment_started = False
        self._detachment_finished = False

    def start(self) -> None:
        """Register the message callback without starting native I/O."""
        with self._lock:
            if self._state in (
                TransportState.STARTING,
                TransportState.RUNNING,
            ):
                return
            if self._state is TransportState.FAILED:
                raise RuntimeError("a failed transport cannot be restarted")
            if self._state is TransportState.STOPPING:
                raise RuntimeError("a stopping transport cannot be started")

            self._state = TransportState.STARTING
            self._accepting_callbacks = False
            self._callback_was_registered = False
            self._stop_deadline_monotonic = None
            self._registration_in_progress = True
            self._detachment_requested = False
            self._detachment_started = False
            self._detachment_finished = False

        registration_error: Exception | None = None
        try:
            self._client.on_message = self._on_message
        except Exception as error:
            registration_error = error
        except BaseException:
            with self._lock:
                self._registration_in_progress = False
                self._accepting_callbacks = False
                self._state = TransportState.FAILED
                launch_detachment = self._claim_detachment_locked()
            if launch_detachment:
                self._launch_detachment_worker()
            raise

        launch_detachment = False
        with self._lock:
            self._registration_in_progress = False
            if registration_error is not None:
                self._accepting_callbacks = False
                self._record_error_locked(
                    registration_error,
                    TransportErrorCategory.STARTUP,
                    "MQTT callback registration failed",
                )
                self._state = TransportState.FAILED
            else:
                self._callback_was_registered = True
                if self._state is TransportState.STARTING:
                    self._accepting_callbacks = True
                    self._state = TransportState.RUNNING

            launch_detachment = self._claim_detachment_locked()

        if launch_detachment:
            self._launch_detachment_worker()

        if registration_error is not None:
            raise RuntimeError(
                "MQTT callback registration failed"
            ) from None

    def stop(self, deadline_monotonic: float) -> TransportStopReport:
        """Close acceptance and request asynchronous callback detachment."""
        deadline = self._validate_deadline(deadline_monotonic)

        with self._lock:
            self._accepting_callbacks = False
            if self._state is TransportState.STOPPED:
                self._stop_deadline_monotonic = None
                self._detachment_requested = False
                self._detachment_started = False
                self._detachment_finished = False
                return self._stop_report_locked(callbacks_quiescent=True)

            if self._stop_deadline_monotonic is None:
                self._stop_deadline_monotonic = deadline
            else:
                self._stop_deadline_monotonic = min(
                    self._stop_deadline_monotonic,
                    deadline,
                )

            if self._state is not TransportState.FAILED:
                self._state = TransportState.STOPPING

            self._detachment_requested = True
            launch_detachment = self._claim_detachment_locked()

        if launch_detachment:
            self._launch_detachment_worker()

        with self._lock:
            return self._stop_report_locked(callbacks_quiescent=False)

    def snapshot(self) -> TransportSnapshot:
        """Return an atomic, sanitized view of local adapter state."""
        with self._lock:
            return TransportSnapshot(
                state=self._state,
                connection_state=self._connection_state,
                environment=self._config.environment,
                connection_name=self._config.connection_name,
                callbacks_received=self._callbacks_received,
                submissions_accepted=self._submissions_accepted,
                submissions_rejected=self._submissions_rejected,
                queue_saturations=self._queue_saturations,
                mapping_failures=self._mapping_failures,
                callbacks_quiescent=self._callbacks_quiescent_locked(),
                latest_error=self._latest_error,
            )

    def _on_message(
        self,
        client: object,
        userdata: object,
        native_message: _NativeMqttMessage,
    ) -> None:
        del client, userdata

        with self._lock:
            self._callbacks_received += 1
            self._callbacks_in_progress += 1
            accepting = (
                self._accepting_callbacks
                and self._state is TransportState.RUNNING
            )

        try:
            if not accepting:
                with self._lock:
                    self._submissions_rejected += 1
                return

            try:
                message = MessageEnvelope(
                    payload=native_message.payload,
                    received_at_utc=datetime.now(timezone.utc),
                    environment=self._config.environment,
                    connection_name=self._config.connection_name,
                    destination=native_message.topic,
                    protocol="mqtt",
                    protocol_version=self._config.protocol_version,
                )
            except Exception as error:
                with self._lock:
                    self._mapping_failures += 1
                    self._record_error_locked(
                        error,
                        TransportErrorCategory.CALLBACK_MAPPING,
                        "MQTT callback mapping failed",
                    )
                return

            try:
                self._sink.submit(message)
            except QueueSaturatedError:
                with self._lock:
                    self._submissions_rejected += 1
                    self._queue_saturations += 1
            except SubmissionRejectedError:
                with self._lock:
                    self._submissions_rejected += 1
            except Exception as error:
                with self._lock:
                    self._record_error_locked(
                        error,
                        TransportErrorCategory.CLIENT_CALLBACK,
                        "message sink callback failed",
                    )
                    self._state = TransportState.FAILED
                    self._accepting_callbacks = False
            else:
                with self._lock:
                    self._submissions_accepted += 1
        finally:
            with self._lock:
                self._callbacks_in_progress -= 1

    def _claim_detachment_locked(self) -> bool:
        if (
            self._detachment_requested
            and not self._registration_in_progress
            and not self._detachment_started
        ):
            self._detachment_started = True
            return True
        return False

    def _launch_detachment_worker(self) -> None:
        try:
            Thread(
                target=self._detach_message_callback,
                name="mqtt-callback-detachment",
                daemon=True,
            ).start()
        except Exception as error:
            with self._lock:
                self._record_error_locked(
                    error,
                    TransportErrorCategory.SHUTDOWN,
                    "MQTT callback detachment worker failed",
                )
                self._state = TransportState.FAILED
                self._detachment_finished = True

    def _detach_message_callback(self) -> None:
        try:
            self._client.on_message = None
        except Exception as error:
            with self._lock:
                self._record_error_locked(
                    error,
                    TransportErrorCategory.SHUTDOWN,
                    "MQTT callback detachment failed",
                )
                self._state = TransportState.FAILED
        finally:
            with self._lock:
                self._detachment_finished = True

    @staticmethod
    def _validate_deadline(value: object) -> int | float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise TypeError(
                "deadline_monotonic must be a non-boolean int or float"
            )
        if isinstance(value, float) and not isfinite(value):
            raise ValueError("deadline_monotonic must be finite")
        return value

    def _record_error_locked(
        self,
        error: Exception,
        category: TransportErrorCategory,
        summary: str,
    ) -> None:
        self._latest_error = SanitizedTransportError(
            category=category,
            exception_type=_sanitized_exception_type(error),
            summary=summary,
            occurred_at_utc=datetime.now(timezone.utc),
        )

    def _callbacks_quiescent_locked(self) -> bool:
        return (
            not self._callback_was_registered
            and self._callbacks_in_progress == 0
        )

    def _stop_report_locked(
        self,
        *,
        callbacks_quiescent: bool,
    ) -> TransportStopReport:
        return TransportStopReport(
            state=self._state,
            callbacks_quiescent=callbacks_quiescent,
            callbacks_in_progress=self._callbacks_in_progress,
            latest_error=self._latest_error,
        )
