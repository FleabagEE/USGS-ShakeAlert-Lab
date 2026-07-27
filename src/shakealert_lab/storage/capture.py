"""Lossless, atomic, never-overwriting native capture storage."""
from __future__ import annotations
from base64 import b64decode,b64encode
from dataclasses import dataclass
from datetime import datetime,timezone
import json,os
from pathlib import Path
from typing import Mapping
from uuid import UUID,uuid4
from shakealert_lab.messaging.inbound import Environment,MessageEnvelope,MetadataScalar
CAPTURE_SCHEMA="quakelogic.shakealert.capture.v1"
class CaptureStorageError(RuntimeError): pass
def _utc(value:datetime|None)->str|None:
    return None if value is None else value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00","Z")
def _headers(values:Mapping[str,MetadataScalar])->dict[str,object]:
    return {k:({"encoding":"base64","value":b64encode(v).decode("ascii")} if isinstance(v,bytes) else v) for k,v in values.items()}
def _decode(values:Mapping[str,object])->dict[str,MetadataScalar]:
    result={}
    for key,value in values.items():
        if isinstance(value,dict) and value.get("encoding")=="base64": result[key]=b64decode(value["value"],validate=True)
        elif type(value) in (str,int,bool): result[key]=value
        else: raise CaptureStorageError("unsupported stored header value")
    return result
@dataclass(frozen=True,slots=True)
class NativeCapture:
    capture_id:UUID
    envelope:MessageEnvelope
    @classmethod
    def create(cls,envelope:MessageEnvelope)->"NativeCapture": return cls(uuid4(),envelope)
    def to_dict(self)->dict[str,object]:
        m=self.envelope
        return {"capture_schema":CAPTURE_SCHEMA,"capture_id":str(self.capture_id),"environment":m.environment.value,
            "endpoint_name":m.connection_name,"protocol":m.protocol,"protocol_version":m.protocol_version,
            "destination":m.destination,"received_utc":_utc(m.received_at_utc),"server_timestamp":_utc(m.server_timestamp),
            "message_id":m.message_id,"correlation_id":m.correlation_id,"redelivered":m.redelivered,
            "delivery_sequence":m.delivery_sequence,"content_type":m.content_type,"payload_size_bytes":m.payload_size,
            "payload_sha256":m.payload_sha256,"headers":_headers(m.verified_metadata),"payload_encoding":"base64",
            "payload_native":b64encode(m.payload).decode("ascii")}
class RawMessageStore:
    def __init__(self,directory:Path)->None:self._directory=directory
    def save(self,capture:NativeCapture)->Path:
        if not isinstance(capture,NativeCapture):raise TypeError("capture must be a NativeCapture")
        self._directory.mkdir(mode=0o750,parents=True,exist_ok=True)
        final=self._directory/f"{capture.capture_id}.json"; temp=self._directory/f".{capture.capture_id}.{uuid4().hex}.tmp"
        payload=json.dumps(capture.to_dict(),sort_keys=True,separators=(",",":")).encode()+b"\n"; descriptor=-1
        try:
            descriptor=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o640)
            with os.fdopen(descriptor,"wb",closefd=True) as stream:
                descriptor=-1;stream.write(payload);stream.flush();os.fsync(stream.fileno())
            os.link(temp,final,follow_symlinks=False)
            directory_fd=os.open(self._directory,os.O_RDONLY|os.O_DIRECTORY)
            try:os.fsync(directory_fd)
            finally:os.close(directory_fd)
        except FileExistsError as error:raise CaptureStorageError("capture already exists; overwrite refused") from error
        except OSError as error:raise CaptureStorageError("native capture could not be preserved") from error
        finally:
            if descriptor>=0:os.close(descriptor)
            try:temp.unlink()
            except FileNotFoundError:pass
        return final
    def load(self,path:Path)->NativeCapture:
        try:
            data=json.loads(path.read_text(encoding="utf-8"))
            if data["capture_schema"]!=CAPTURE_SCHEMA:raise CaptureStorageError("unsupported capture schema")
            payload=b64decode(data["payload_native"],validate=True)
            if len(payload)!=data["payload_size_bytes"]:raise CaptureStorageError("stored payload size does not match")
            server=data["server_timestamp"]
            envelope=MessageEnvelope(payload=payload,received_at_utc=datetime.fromisoformat(data["received_utc"].replace("Z","+00:00")),
                environment=Environment(data["environment"]),connection_name=data["endpoint_name"],destination=data["destination"],
                protocol=data["protocol"],protocol_version=data["protocol_version"],message_id=data["message_id"],
                correlation_id=data["correlation_id"],server_timestamp=None if server is None else datetime.fromisoformat(server.replace("Z","+00:00")),
                redelivered=data["redelivered"],content_type=data["content_type"],delivery_sequence=data["delivery_sequence"],
                verified_metadata=_decode(data["headers"]))
            capture=NativeCapture(UUID(data["capture_id"]),envelope)
            if envelope.payload_sha256!=data["payload_sha256"]:raise CaptureStorageError("stored payload hash does not match")
            return capture
        except CaptureStorageError:raise
        except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError) as error:raise CaptureStorageError("capture record is invalid") from error
