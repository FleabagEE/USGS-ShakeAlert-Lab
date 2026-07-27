from datetime import datetime,timezone
from pathlib import Path
from shakealert_lab.classifier import ClassificationEvidence,MessageEnvironment,classify_environment
from shakealert_lab.messaging.inbound import Environment,MessageEnvelope
from shakealert_lab.storage.capture import NativeCapture,RawMessageStore
from shakealert_lab.validation import CaptureValidator
def test_preserve_before_validate_pipeline(tmp_path:Path)->None:
    message=MessageEnvelope(payload=b"uninterpreted",received_at_utc=datetime.now(timezone.utc),environment=Environment.UNKNOWN,connection_name="unverified")
    store=RawMessageStore(tmp_path);capture=NativeCapture.create(message);path=store.save(capture)
    result=CaptureValidator(maximum_payload_bytes=1).validate(capture)
    assert path.exists() and not result.valid
    assert classify_environment((ClassificationEvidence("endpoint",MessageEnvironment.UNKNOWN),)) is MessageEnvironment.UNKNOWN
