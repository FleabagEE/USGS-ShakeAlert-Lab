"""Protocol-neutral validation, duplicate, and sequence state."""
from dataclasses import dataclass
from datetime import datetime,timedelta,timezone
from enum import Enum
from typing import Hashable
from shakealert_lab.storage.capture import NativeCapture
class MessageDisposition(Enum):
    NEW="new";EXACT_DUPLICATE="exact_duplicate";REDELIVERY="redelivery";NEWER_UPDATE="newer_update";STALE_UPDATE="stale_update";OUT_OF_ORDER_UPDATE="out_of_order_update";CANCELLATION="cancellation";HEARTBEAT="heartbeat";UNKNOWN="unknown"
@dataclass(frozen=True,slots=True)
class ValidationIssue:code:str;detail:str
@dataclass(frozen=True,slots=True)
class ValidationResult:valid:bool;issues:tuple[ValidationIssue,...]
class CaptureValidator:
    def __init__(self,*,maximum_payload_bytes:int,future_tolerance:timedelta=timedelta(seconds=1))->None:
        if maximum_payload_bytes<=0:raise ValueError("maximum_payload_bytes must be positive")
        self._maximum=maximum_payload_bytes;self._future_tolerance=future_tolerance
    def validate(self,capture:NativeCapture,*,now_utc:datetime|None=None)->ValidationResult:
        m=capture.envelope;now=datetime.now(timezone.utc) if now_utc is None else now_utc;issues=[]
        if m.payload_size>self._maximum:issues.append(ValidationIssue("payload_oversized","payload exceeds configured verified limit"))
        if m.received_at_utc>now+self._future_tolerance:issues.append(ValidationIssue("receive_time_future","receive time exceeds configured tolerance"))
        if m.server_timestamp is not None and m.server_timestamp>now+self._future_tolerance:issues.append(ValidationIssue("server_time_future","server time exceeds configured tolerance"))
        return ValidationResult(not issues,tuple(issues))
class SequenceTracker:
    def __init__(self)->None:self._hashes:set[str]=set();self._latest:dict[Hashable,int]={}
    def classify(self,capture:NativeCapture,*,event_key:Hashable|None=None,sequence:int|None=None,cancellation:bool=False,heartbeat:bool=False)->MessageDisposition:
        digest=capture.envelope.payload_sha256
        if digest in self._hashes:return MessageDisposition.REDELIVERY if capture.envelope.redelivered else MessageDisposition.EXACT_DUPLICATE
        self._hashes.add(digest)
        if heartbeat:return MessageDisposition.HEARTBEAT
        if cancellation:return MessageDisposition.CANCELLATION
        if event_key is None or sequence is None:return MessageDisposition.NEW
        prior=self._latest.get(event_key)
        if prior is None:self._latest[event_key]=sequence;return MessageDisposition.NEW
        if sequence>prior:self._latest[event_key]=sequence;return MessageDisposition.NEWER_UPDATE
        if sequence==prior:return MessageDisposition.STALE_UPDATE
        return MessageDisposition.OUT_OF_ORDER_UPDATE
