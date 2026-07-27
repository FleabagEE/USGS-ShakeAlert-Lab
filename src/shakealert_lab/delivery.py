"""Acknowledgment abstraction with no default transport semantics."""
from enum import Enum
from typing import Protocol
from shakealert_lab.storage.capture import NativeCapture
class AcknowledgmentDecision(Enum):
    ACKNOWLEDGE="acknowledge";REJECT="reject";DEFER="defer"
class Acknowledger(Protocol):
    def apply(self,capture:NativeCapture,decision:AcknowledgmentDecision)->None:...
class UnverifiedAcknowledgment(RuntimeError):pass
class DeferredAcknowledger:
    """Fail closed until native acknowledgment behavior is verified."""
    def apply(self,capture:NativeCapture,decision:AcknowledgmentDecision)->None:
        raise UnverifiedAcknowledgment("acknowledgment behavior is not verified")
