from datetime import datetime,timedelta,timezone
from pathlib import Path
from queue import Queue
from random import Random
from uuid import UUID
import json,logging
import pytest
from shakealert_lab.classifier import ClassificationEvidence,MessageEnvironment,classify_environment
from shakealert_lab.config import ConfigurationError,load_config
from shakealert_lab.credentials import CredentialError,inspect_credentials
from shakealert_lab.health import HeartbeatMonitor
from shakealert_lab.logging_setup import JsonFormatter
from shakealert_lab.messaging.inbound import Environment,MessageEnvelope
from shakealert_lab.metrics import Metrics
from shakealert_lab.normalization import normalize_verified_fields
from shakealert_lab.reliability import BackoffPolicy
from shakealert_lab.safety import SafetyInterlockError,enforce_safety_interlock
from shakealert_lab.security import redact_mapping
from shakealert_lab.storage.capture import CaptureStorageError,NativeCapture,RawMessageStore
from shakealert_lab.validation import CaptureValidator,MessageDisposition,SequenceTracker

def envelope(payload:bytes=b"native",**changes:object)->MessageEnvelope:
    values={"payload":payload,"received_at_utc":datetime(2026,1,1,tzinfo=timezone.utc),"environment":Environment.SCENARIO,"connection_name":"scenario","destination":"assigned","protocol":"verified","protocol_version":"verified","verified_metadata":{"binary":b"\x00\xff"}}
    values.update(changes);return MessageEnvelope(**values) # type: ignore[arg-type]
def config_text(**changes:str)->str:
    values={"host":"verified.example","protocol":"verified","version":"1","destination":"assigned"};values.update(changes)
    return f'''[endpoint]\nname="scenario"\nenvironment="scenario"\nhost="{values['host']}"\nport=1234\nprotocol="{values['protocol']}"\nprotocol_version="{values['version']}"\ndestination="{values['destination']}"\ntls_required=true\nmaximum_payload_bytes=4096\nconnect_authorized=false\n[credentials]\n[storage]\nnative_directory="native"\nnormalized_directory="normalized"\nrejected_directory="rejected"\nlog_directory="logs"\n[runtime]\nqueue_capacity=8\nshutdown_timeout_seconds=2\n'''
def test_config_is_protocol_neutral_and_separates_paths(tmp_path:Path)->None:
    path=tmp_path/"lab.toml";path.write_text(config_text());config=load_config(path)
    assert config.endpoint.protocol=="verified" and not config.endpoint.connect_authorized
    assert config.native_directory==tmp_path/"native"
@pytest.mark.parametrize("field",["host","protocol","version","destination"])
def test_config_rejects_unknown_empty_required_values(tmp_path:Path,field:str)->None:
    path=tmp_path/"bad.toml";path.write_text(config_text(**{field:""}))
    with pytest.raises(ConfigurationError):load_config(path)
def test_safety_interlock_is_exact()->None:
    enforce_safety_interlock({"ALLOW_OPERATIONAL_OUTPUTS":"false"})
    with pytest.raises(SafetyInterlockError):enforce_safety_interlock({})
def test_credential_status_never_contains_value(tmp_path:Path)->None:
    from shakealert_lab.config import CredentialPaths
    secret=tmp_path/"secret";secret.write_text("sensitive");secret.chmod(0o600)
    status=inspect_credentials(CredentialPaths(password=secret));text="\n".join(status.display_lines())
    assert "sensitive" not in text and "password: present" in text
def test_credential_permissions_fail_closed(tmp_path:Path)->None:
    from shakealert_lab.config import CredentialPaths
    secret=tmp_path/"secret";secret.write_text("x");secret.chmod(0o644)
    with pytest.raises(CredentialError):inspect_credentials(CredentialPaths(password=secret))
def test_capture_round_trip_is_lossless_and_atomic(tmp_path:Path)->None:
    capture=NativeCapture.create(envelope(b"\x00native\xff"));store=RawMessageStore(tmp_path);path=store.save(capture);loaded=store.load(path)
    assert loaded==capture and path.stat().st_mode&0o777==0o640
    with pytest.raises(CaptureStorageError):store.save(capture)
def test_capture_detects_payload_tampering(tmp_path:Path)->None:
    capture=NativeCapture.create(envelope());store=RawMessageStore(tmp_path);path=store.save(capture);data=json.loads(path.read_text());data["payload_sha256"]="0"*64;path.write_text(json.dumps(data))
    with pytest.raises(CaptureStorageError):store.load(path)
def test_classification_conflict_is_unknown()->None:
    evidence=(ClassificationEvidence("endpoint",MessageEnvironment.LIVE),ClassificationEvidence("payload",MessageEnvironment.TEST))
    assert classify_environment(evidence) is MessageEnvironment.UNKNOWN
def test_validation_preserves_oversized_capture()->None:
    capture=NativeCapture.create(envelope(b"xx"));result=CaptureValidator(maximum_payload_bytes=1).validate(capture,now_utc=datetime(2026,1,1,tzinfo=timezone.utc))
    assert not result.valid and capture.envelope.payload==b"xx"
def test_sequence_duplicate_update_and_out_of_order()->None:
    tracker=SequenceTracker();first=NativeCapture.create(envelope(b"1"));newer=NativeCapture.create(envelope(b"2"));older=NativeCapture.create(envelope(b"3"))
    assert tracker.classify(first,event_key="e",sequence=1) is MessageDisposition.NEW
    assert tracker.classify(first,event_key="e",sequence=1) is MessageDisposition.EXACT_DUPLICATE
    assert tracker.classify(newer,event_key="e",sequence=2) is MessageDisposition.NEWER_UPDATE
    assert tracker.classify(older,event_key="e",sequence=1) is MessageDisposition.OUT_OF_ORDER_UPDATE
def test_normalization_keeps_provenance_and_only_supplied_fields()->None:
    capture=NativeCapture.create(envelope());item=normalize_verified_fields(capture_id=capture.capture_id,received_utc=capture.envelope.received_at_utc,environment=MessageEnvironment.SCENARIO,disposition=MessageDisposition.NEW,message_type=None,verified_fields={})
    assert item.capture_id==capture.capture_id and dict(item.fields)=={}
def test_backoff_is_bounded_and_jittered()->None:
    policy=BackoffPolicy(initial_seconds=1,maximum_seconds=4,jitter_fraction=.2)
    assert 0.8<=policy.delay(0,random=Random(1))<=1.2 and policy.delay(99,random=Random(1))<=4
def test_heartbeat_timeout()->None:
    start=datetime(2026,1,1,tzinfo=timezone.utc);monitor=HeartbeatMonitor(timedelta(seconds=5));monitor.record(start)
    assert monitor.healthy(start+timedelta(seconds=5)) and not monitor.healthy(start+timedelta(seconds=6))
def test_metrics_snapshot_is_copy()->None:
    metrics=Metrics();metrics.increment("messages");snapshot=metrics.snapshot();snapshot["messages"]=9;assert metrics.snapshot()["messages"]==1
def test_redaction_and_json_logging()->None:
    assert redact_mapping({"password":"x","payload":b"abc"})=={"password":"<redacted>","payload":"<bytes:3>"}
    record=logging.LogRecord("lab",logging.INFO,"",0,"ok",(),None);record.fields={"token":"x"};assert "x" not in JsonFormatter().format(record)
