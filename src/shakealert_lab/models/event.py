"""Protocol-independent verified field container."""
from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping
Scalar = str | int | float | bool | None
@dataclass(frozen=True, slots=True)
class VerifiedFieldSet:
    schema_identifier: str
    values: Mapping[str, Scalar]
    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))
