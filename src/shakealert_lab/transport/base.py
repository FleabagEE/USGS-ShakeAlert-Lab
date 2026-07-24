"""Protocol-neutral message transport contracts."""

from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum
from typing import Protocol

from shakealert_lab.messaging.inbound import Environment, MessageEnvelope


__all__ = [
    "ConnectionState",
    "MessageSink",
    "MessageTransport",
    "SanitizedTransportError",
    "TransportErrorCategory",
    "TransportSnapshot",
    "TransportState",
    "TransportStopReport",
]


class TransportState(Enum):
    """Lifecycle state of one transport adapter instance."""

    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    STOPPING = "stopping"
    FAILED = "failed"


class ConnectionState(Enum):
    """Verified connection observation reported by a transport adapter."""

    UNKNOWN = "unknown"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    DEGRADED = "degraded"


class TransportErrorCategory(Enum):
    """Sanitized category for a transport-owned failure."""

    STARTUP = "startup"
    SHUTDOWN = "shutdown"
    CONNECTION = "connection"
    CALLBACK_MAPPING = "callback_mapping"
    CLIENT_CALLBACK = "client_callback"
    UNKNOWN = "unknown"


def _contains_control_characters(value: str) -> bool:
    """Return whether a string contains ASCII control characters."""
    return any(
        ord(character) < 32 or ord(character) == 127
        for character in value
    )


def _validate_sanitized_text(
    value: object,
    *,
    name: str,
    maximum_length: int | None = None,
) -> None:
    """Validate a nonempty, trimmed, control-free string."""
    if not isinstance(value, str):
        raise TypeError(f"{name} must be a string")

    if not value or value != value.strip():
        raise ValueError(f"{name} must be non-empty and trimmed")

    if _contains_control_characters(value):
        raise ValueError(f"{name} must contain no control characters")

    if maximum_length is not None and len(value) > maximum_length:
        raise ValueError(
            f"{name} must be at most {maximum_length} characters"
        )


def _validate_utc_timestamp(value: object, *, name: str) -> None:
    """Validate a timezone-aware timestamp with a zero UTC offset."""
    if not isinstance(value, datetime):
        raise TypeError(f"{name} must be a datetime")

    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{name} must be timezone-aware")

    if value.utcoffset() != timedelta(0):
        raise ValueError(f"{name} must be UTC")


def _validate_counter(value: object, *, name: str) -> None:
    """Validate a nonnegative integer counter, excluding bool."""
    if type(value) is not int:
        raise TypeError(f"{name} must be an integer")

    if value < 0:
        raise ValueError(f"{name} must be nonnegative")


@dataclass(frozen=True, slots=True)
class SanitizedTransportError:
    """Sanitized details for the latest transport-owned failure."""

    category: TransportErrorCategory
    exception_type: str
    summary: str
    occurred_at_utc: datetime

    def __post_init__(self) -> None:
        """Validate the sanitized transport error."""
        if not isinstance(self.category, TransportErrorCategory):
            raise TypeError(
                "category must be a TransportErrorCategory"
            )

        _validate_sanitized_text(
            self.exception_type,
            name="exception_type",
            maximum_length=128,
        )

        if not self.exception_type.isidentifier():
            raise ValueError(
                "exception_type must be an unqualified class name"
            )

        _validate_sanitized_text(
            self.summary,
            name="summary",
            maximum_length=256,
        )

        _validate_utc_timestamp(
            self.occurred_at_utc,
            name="occurred_at_utc",
        )


@dataclass(frozen=True, slots=True)
class TransportSnapshot:
    """Atomic sanitized state for one transport adapter instance."""

    state: TransportState
    connection_state: ConnectionState
    environment: Environment
    connection_name: str
    callbacks_received: int
    submissions_accepted: int
    submissions_rejected: int
    queue_saturations: int
    mapping_failures: int
    callbacks_quiescent: bool
    latest_error: SanitizedTransportError | None

    def __post_init__(self) -> None:
        """Validate snapshot types and counter invariants."""
        if not isinstance(self.state, TransportState):
            raise TypeError("state must be a TransportState")

        if not isinstance(self.connection_state, ConnectionState):
            raise TypeError(
                "connection_state must be a ConnectionState"
            )

        if not isinstance(self.environment, Environment):
            raise TypeError("environment must be an Environment")

        _validate_sanitized_text(
            self.connection_name,
            name="connection_name",
        )

        for value, name in (
            (self.callbacks_received, "callbacks_received"),
            (self.submissions_accepted, "submissions_accepted"),
            (self.submissions_rejected, "submissions_rejected"),
            (self.queue_saturations, "queue_saturations"),
            (self.mapping_failures, "mapping_failures"),
        ):
            _validate_counter(value, name=name)

        if type(self.callbacks_quiescent) is not bool:
            raise TypeError("callbacks_quiescent must be a bool")

        if (
            self.latest_error is not None
            and not isinstance(
                self.latest_error,
                SanitizedTransportError,
            )
        ):
            raise TypeError(
                "latest_error must be a SanitizedTransportError or None"
            )

        if self.queue_saturations > self.submissions_rejected:
            raise ValueError(
                "queue_saturations must not exceed submissions_rejected"
            )

        accounted_callbacks = (
            self.submissions_accepted
            + self.submissions_rejected
            + self.mapping_failures
        )

        if accounted_callbacks > self.callbacks_received:
            raise ValueError(
                "callback outcomes must not exceed callbacks_received"
            )


@dataclass(frozen=True, slots=True)
class TransportStopReport:
    """Result of stopping one transport adapter instance."""

    state: TransportState
    callbacks_quiescent: bool
    callbacks_in_progress: int
    latest_error: SanitizedTransportError | None

    def __post_init__(self) -> None:
        """Validate stop-result types and invariants."""
        if not isinstance(self.state, TransportState):
            raise TypeError("state must be a TransportState")

        if self.state not in (
            TransportState.STOPPED,
            TransportState.STOPPING,
            TransportState.FAILED,
        ):
            raise ValueError(
                "stop report state must be STOPPED, STOPPING, or FAILED"
            )

        if type(self.callbacks_quiescent) is not bool:
            raise TypeError("callbacks_quiescent must be a bool")

        _validate_counter(
            self.callbacks_in_progress,
            name="callbacks_in_progress",
        )

        if (
            self.latest_error is not None
            and not isinstance(
                self.latest_error,
                SanitizedTransportError,
            )
        ):
            raise TypeError(
                "latest_error must be a SanitizedTransportError or None"
            )

        if self.callbacks_quiescent and self.callbacks_in_progress != 0:
            raise ValueError(
                "quiescent callbacks require zero callbacks in progress"
            )

        if self.state is TransportState.STOPPED and (
            not self.callbacks_quiescent
            or self.callbacks_in_progress != 0
        ):
            raise ValueError(
                "STOPPED requires quiescent callbacks and none in progress"
            )


class MessageSink(Protocol):
    """Nonblocking application handoff for inbound messages."""
    def submit(self, message: MessageEnvelope) -> None:

        """Submit one envelope without waiting for downstream completion.



        Return normally only when the envelope is accepted into the

        runtime's bounded in-memory queue.



        A runtime implementation raises its protocol-neutral submission

        rejection exception when it is not accepting messages, its more

        specific queue-saturation exception when the queue is full, and

        TypeError when message is not a MessageEnvelope.



        Successful return does not mean durable preservation, parsing,

        routing, processing, or acknowledgment.


        """
        ...


class MessageTransport(Protocol):
    """Protocol-neutral lifecycle and observability contract."""

    def start(self) -> None:
        """Start this adapter without controlling RuntimeService.

        Starting from STOPPED begins adapter startup. Calls made while the
        adapter is STARTING or RUNNING are idempotent and return immediately.
        A call made while STOPPING is invalid.

        FAILED is latched. Restart from FAILED is prohibited and recovery
        requires a new adapter instance or process restart.

        Return from this method does not imply that the adapter is connected,
        authenticated, authorized, subscribed, or receiving messages.
        Readiness and connection observations are obtained through snapshot().

        A terminal startup failure must be recorded as a sanitized transport
        error. A public failure must not expose or chain a native exception
        object, module name, raw native exception text, credential, endpoint,
        payload, or header.
        """
        ...

    def stop(
        self,
        deadline_monotonic: float,
    ) -> TransportStopReport:
        """Stop this adapter without controlling RuntimeService.

        deadline_monotonic is an absolute value from the caller's monotonic
        clock. It must be a finite non-boolean int or float. Implementations
        must raise TypeError for bool or non-numeric values and ValueError for
        NaN or positive or negative infinity. Negative or already-expired
        finite deadlines are valid and require a prompt non-waiting result.

        The caller owns the shutdown deadline. The adapter must not wait past
        the supplied deadline. For repeated calls during the same stop
        sequence, the effective deadline must never move later: an earlier
        subsequent deadline may tighten the stop, but a later one must not
        extend it.

        The method is idempotent and remains allowed after FAILED. It closes
        the adapter's acceptance boundary, attempts to establish callback
        quiescence within the supplied deadline, and reports STOPPED,
        STOPPING, or FAILED.

        STOPPED means callbacks are quiescent and none remain in progress.
        STOPPING means quiescence was not established by the deadline.
        FAILED remains latched even when callback quiescence is established.

        This method does not stop or drain RuntimeService and defines no
        acknowledgment, retry, reconnect, pause, disconnect, unsubscribe,
        TLS, authentication, or subscription behavior.
        """
        ...

    def snapshot(self) -> TransportSnapshot:
        """Return an atomic sanitized transport snapshot.

        Snapshot construction must use local adapter state only. It must not
        perform native-client, network, filesystem, runtime, or endpoint
        operations.

        Runtime submission rejection and queue saturation are represented by
        counters. They are not transport errors and must not replace
        latest_error.

        The callback accounting invariant is:

            submissions_accepted
            + submissions_rejected
            + mapping_failures
            <= callbacks_received

        Equality is not required because callbacks may still be in progress
        or may not yet have a final disposition.
        """
        ...