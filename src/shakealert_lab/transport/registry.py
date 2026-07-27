"""Explicit adapter registry; no transport is guessed or selected by default."""
from collections.abc import Callable
from shakealert_lab.config import LabConfig
from shakealert_lab.transport.base import MessageSink,MessageTransport
TransportFactory=Callable[[LabConfig,MessageSink],MessageTransport]
class UnknownTransportError(LookupError):pass
class TransportRegistry:
    def __init__(self)->None:self._factories:dict[tuple[str,str],TransportFactory]={}
    def register(self,protocol:str,version:str,factory:TransportFactory)->None:
        key=(protocol.casefold(),version)
        if key in self._factories:raise ValueError("transport factory already registered")
        self._factories[key]=factory
    def create(self,config:LabConfig,sink:MessageSink)->MessageTransport:
        key=(config.endpoint.protocol.casefold(),config.endpoint.protocol_version)
        try:return self._factories[key](config,sink)
        except KeyError:raise UnknownTransportError("no verified adapter is registered for configured protocol/version") from None
