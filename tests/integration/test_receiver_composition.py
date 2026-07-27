from datetime import datetime,timezone
from pathlib import Path
from time import monotonic
from shakealert_lab.messaging.inbound import Environment,MessageEnvelope
from shakealert_lab.receiver import PreservingRouter
from shakealert_lab.storage.capture import RawMessageStore
from shakealert_lab.validation import CaptureValidator
def test_unknown_destination_is_preserved_before_validation(tmp_path:Path)->None:
    router=PreservingRouter(RawMessageStore(tmp_path),CaptureValidator(maximum_payload_bytes=1))
    router.route(MessageEnvelope(payload=b"oversized",received_at_utc=datetime.now(timezone.utc),environment=Environment.UNKNOWN,connection_name="unknown",destination=None))
    assert len(list(tmp_path.glob("*.json")))==1
