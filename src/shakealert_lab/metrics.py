"""Thread-safe local laboratory counters and gauges."""
from threading import RLock
class Metrics:
    def __init__(self)->None:self._lock=RLock();self._values:dict[str,int|float|str|bool|None]={}
    def set(self,name:str,value:int|float|str|bool|None)->None:
        with self._lock:self._values[name]=value
    def increment(self,name:str,amount:int=1)->None:
        with self._lock:self._values[name]=int(self._values.get(name,0))+amount
    def snapshot(self)->dict[str,int|float|str|bool|None]:
        with self._lock:return dict(self._values)
