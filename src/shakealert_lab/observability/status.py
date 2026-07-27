"""Required dashboard status schema with explicit unknown values."""
from dataclasses import asdict,dataclass
@dataclass(frozen=True,slots=True)
class ConnectionStatus:
    name:str;environment:str;connected:bool|None=None;heartbeat_healthy:bool|None=None;last_message_utc:str|None=None
    messages_received:int=0;reconnects:int=0;protocol_version:str|None=None;tls_status:str|None=None
@dataclass(frozen=True,slots=True)
class LaboratoryStatus:
    production:ConnectionStatus;scenario:ConnectionStatus;clock_offset_ms:float|None;disk_free_bytes:int;process_uptime_seconds:float
    def to_dict(self)->dict[str,object]:return asdict(self)
