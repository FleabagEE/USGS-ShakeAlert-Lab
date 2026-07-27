"""Minimal structured JSON logging with field redaction."""
import json,logging
from datetime import datetime,timezone
from shakealert_lab.security import redact_mapping
class JsonFormatter(logging.Formatter):
    def format(self,record:logging.LogRecord)->str:
        fields={"timestamp_utc":datetime.now(timezone.utc).isoformat(),"level":record.levelname,"logger":record.name,"message":record.getMessage()};extra=getattr(record,"fields",{})
        if isinstance(extra,dict):fields["fields"]=redact_mapping(extra)
        return json.dumps(fields,sort_keys=True,separators=(",",":"))
def configure_logging(level:int=logging.INFO)->None:
    handler=logging.StreamHandler();handler.setFormatter(JsonFormatter());root=logging.getLogger();root.handlers[:]=[handler];root.setLevel(level)
