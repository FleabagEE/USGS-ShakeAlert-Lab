"""Explicit unit conversion registry with no assumed ShakeAlert units."""
from collections.abc import Callable
class UnknownConversionError(LookupError):pass
class UnitConversionRegistry:
    def __init__(self)->None:self._converters:dict[tuple[str,str],Callable[[float],float]]={}
    def register(self,source_unit:str,target_unit:str,converter:Callable[[float],float])->None:
        key=(source_unit,target_unit)
        if key in self._converters:raise ValueError("conversion already registered")
        self._converters[key]=converter
    def convert(self,value:float,source_unit:str,target_unit:str)->float:
        try:return self._converters[(source_unit,target_unit)](value)
        except KeyError:raise UnknownConversionError("no verified unit conversion is registered") from None
