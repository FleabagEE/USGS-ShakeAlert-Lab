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
    assert "Optional<FiniteFault>" in source
    for name in ("FiniteFault.java", "FaultSegment.java", "FaultVertex.java"):
        typed_source = (TOOLS / name).read_text(encoding="utf-8")
        assert "record " in typed_source
        assert "Map<String, Object>" not in typed_source


def test_finite_fault_profile_is_explicitly_allowlisted_and_bounded() -> None:
    source = (TOOLS / "ShakeAlertEventParser.java").read_text(encoding="utf-8")
    for control in (
        "FINITE_FAULT_ATTRIBUTES",
        '"atten_geom"',
        '"segment_number"',
        '"segment_shape"',
        'Set.of("lat", "lon", "depth")',
        'MAXIMUM_FAULT_INFO = 1',
        'MAXIMUM_FINITE_FAULTS = 1',
        'MAXIMUM_SEGMENTS = 1',
        'MAXIMUM_VERTICES_PER_SEGMENT = 256',
        'MAXIMUM_TOTAL_VERTICES = 256',
        '"line".equals(segmentShape)',
        '"true".equals(requiredAttribute(finiteFault, "atten_geom"))',
    ):
        assert control in source


def test_parser_has_no_jms_or_transport_native_dependency() -> None:
    for name in ("ShakeAlertEventParser.java", "ShakeAlertEventProcessor.java",
                 "ShakeAlertEventUpdate.java"):
        source = (TOOLS / name).read_text(encoding="utf-8")
        assert "javax.jms" not in source
        assert "org.apache.activemq" not in source
