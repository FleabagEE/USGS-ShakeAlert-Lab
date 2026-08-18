from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"


def test_message_envelope_has_no_jms_or_activemq_dependency() -> None:
    source = (TOOLS / "MessageEnvelope.java").read_text(encoding="utf-8")
    assert "javax.jms" not in source
    assert "org.apache.activemq" not in source
    assert "payload.clone()" in source
    assert "MessageDigest.getInstance(\"SHA-256\")" in source


def test_xml_parser_is_hardened_and_offline() -> None:
    source = (TOOLS / "ShakeAlertEventParser.java").read_text(encoding="utf-8")
    for control in (
        "disallow-doctype-decl", "external-general-entities",
        "external-parameter-entities", "load-external-dtd",
        "ACCESS_EXTERNAL_DTD", "ACCESS_EXTERNAL_SCHEMA",
        "setXIncludeAware(false)", "setExpandEntityReferences(false)",
        "CodingErrorAction.REPORT",
    ):
        assert control in source


def test_capture_commit_precedes_envelope_and_parser_in_receiver_source() -> None:
    source = (TOOLS / "ScenarioOpenWireReceiver.java").read_text(encoding="utf-8")
    committed = source.index("NativeCaptureCommit committed = capture(")
    envelope = source.index("return new MessageEnvelope(", committed)
    processing = source.index("processor.process(envelope)", envelope)
    assert committed < envelope < processing


def test_domain_model_is_typed_not_arbitrary_map() -> None:
    source = (TOOLS / "ShakeAlertEventUpdate.java").read_text(encoding="utf-8")
    assert "record ShakeAlertEventUpdate" in source
    assert "Map<String, Object>" not in source
    assert "CoreInfo" in source
    assert "Contributor" in source


def test_parser_has_no_jms_or_transport_native_dependency() -> None:
    for name in ("ShakeAlertEventParser.java", "ShakeAlertEventProcessor.java",
                 "ShakeAlertEventUpdate.java"):
        source = (TOOLS / name).read_text(encoding="utf-8")
        assert "javax.jms" not in source
        assert "org.apache.activemq" not in source
