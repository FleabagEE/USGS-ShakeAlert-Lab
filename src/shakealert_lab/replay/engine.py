"""Safe replay to an internal queue with timing control."""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime
from queue import Queue
from threading import Condition
from time import sleep
from typing import Callable,Protocol
from shakealert_lab.storage.capture import NativeCapture
class ReplaySink(Protocol):
    def send(self,capture:NativeCapture)->None:...
class InternalQueueSink:
    def __init__(self,queue:Queue[NativeCapture])->None:self._queue=queue
    def send(self,capture:NativeCapture)->None:self._queue.put_nowait(capture)
@dataclass(frozen=True,slots=True)
class ReplayOptions:
    speed:float=1.0
    def __post_init__(self)->None:
        if self.speed<=0:raise ValueError("speed must be positive")
class ReplayController:
    def __init__(self)->None:self._condition=Condition();self._paused=False;self._steps=0;self._stopped=False
    def pause(self)->None:
        with self._condition:self._paused=True
    def resume(self)->None:
        with self._condition:self._paused=False;self._condition.notify_all()
    def step(self)->None:
        with self._condition:self._steps+=1;self._condition.notify_all()
    def stop(self)->None:
        with self._condition:self._stopped=True;self._condition.notify_all()
    def wait_permission(self)->bool:
        with self._condition:
            while self._paused and self._steps==0 and not self._stopped:self._condition.wait()
            if self._stopped:return False
            if self._steps:self._steps-=1
            return True
def replay(captures:list[NativeCapture],sink:ReplaySink,*,options:ReplayOptions=ReplayOptions(),controller:ReplayController|None=None,
    predicate:Callable[[NativeCapture],bool]|None=None,sleeper:Callable[[float],None]=sleep)->int:
    selected=[c for c in captures if predicate is None or predicate(c)];sent=0;previous:datetime|None=None
    for capture in selected:
        if controller is not None and not controller.wait_permission():break
        current=capture.envelope.received_at_utc
        if previous is not None:sleeper(max(0.0,(current-previous).total_seconds()/options.speed))
        sink.send(capture);sent+=1;previous=current
    return sent
