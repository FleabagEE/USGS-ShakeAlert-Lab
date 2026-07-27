"""Protocol-neutral passive receiver composition."""
from __future__ import annotations
from datetime import datetime,timezone
from time import monotonic
from typing import Protocol
from shakealert_lab.messaging.inbound import MessageEnvelope
from shakealert_lab.runtime.service import RuntimeService,ShutdownReport
from shakealert_lab.storage.capture import NativeCapture,RawMessageStore
from shakealert_lab.transport.base import MessageTransport,TransportStopReport
from shakealert_lab.validation import CaptureValidator,ValidationResult
class PostCaptureHandler(Protocol):
    def handle(self,capture:NativeCapture,validation:ValidationResult)->None:...
class PreservingRouter:
    """Store every destination before optional interpretation."""
    def __init__(self,store:RawMessageStore,validator:CaptureValidator,handler:PostCaptureHandler|None=None)->None:
        self._store=store;self._validator=validator;self._handler=handler
    def route(self,message:MessageEnvelope)->None:
        capture=NativeCapture.create(message);self._store.save(capture);result=self._validator.validate(capture)
        if self._handler is not None:self._handler.handle(capture,result)
class ReceiverApplication:
    """Start worker before transport; stop transport before draining runtime."""
    def __init__(self,runtime:RuntimeService,transport:MessageTransport,shutdown_timeout_seconds:float)->None:
        self.runtime=runtime;self.transport=transport;self._timeout=shutdown_timeout_seconds
    def start(self)->None:self.runtime.start();self.transport.start()
    def stop(self)->tuple[TransportStopReport,ShutdownReport]:
        transport_report=self.transport.stop(monotonic()+self._timeout);return transport_report,self.runtime.stop()
