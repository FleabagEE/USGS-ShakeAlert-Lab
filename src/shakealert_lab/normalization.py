"""Transport-neutral provenance model without fabricated message fields."""
from dataclasses import dataclass
from datetime import datetime
from types import MappingProxyType
from typing import Mapping
from uuid import UUID
from shakealert_lab.classifier import MessageEnvironment
from shakealert_lab.validation import MessageDisposition
Scalar=str|int|float|bool|None
@dataclass(frozen=True,slots=True)
class NormalizedMessage:
    capture_id:UUID;received_utc:datetime;environment:MessageEnvironment;disposition:MessageDisposition;message_type:str|None;fields:Mapping[str,Scalar]
    def __post_init__(self)->None:object.__setattr__(self,"fields",MappingProxyType(dict(self.fields)))
def normalize_verified_fields(*,capture_id:UUID,received_utc:datetime,environment:MessageEnvironment,disposition:MessageDisposition,message_type:str|None,verified_fields:Mapping[str,Scalar])->NormalizedMessage:
    return NormalizedMessage(capture_id,received_utc,environment,disposition,message_type,verified_fields)
