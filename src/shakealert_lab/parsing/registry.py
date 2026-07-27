"""Schema parser registry with no default message schema."""
from collections.abc import Callable
from typing import Mapping
Parser=Callable[[bytes],Mapping[str,str|int|float|bool|None]]
class UnknownSchemaError(LookupError):pass
class ParserRegistry:
    def __init__(self)->None:self._parsers:dict[tuple[str,str],Parser]={}
    def register(self,content_type:str,schema_identifier:str,parser:Parser)->None:
        key=(content_type.casefold(),schema_identifier)
        if key in self._parsers:raise ValueError("parser already registered")
        self._parsers[key]=parser
    def parse(self,payload:bytes,*,content_type:str,schema_identifier:str)->Mapping[str,str|int|float|bool|None]:
        try:return self._parsers[(content_type.casefold(),schema_identifier)](payload)
        except KeyError:raise UnknownSchemaError("no verified parser is registered for this schema") from None
