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
    capture = source.index("return capture(")
    envelope = source.index("MessageEnvelope envelope = new MessageEnvelope(", capture)
    processing = source.index("processor.process(envelope)", envelope)
    assert capture < envelope < processing


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


def test_follow_up_dispatch_and_profile_are_explicit_and_bounded() -> None:
    dispatch = (TOOLS / "ShakeAlertMessageParser.java").read_text(encoding="utf-8")
    parser = (TOOLS / "ShakeAlertFollowUpParser.java").read_text(encoding="utf-8")
    assert '"follow_up".equals(messageType)' in dispatch
    assert 'FOLLOW_UP_VERSION.equals(version)' in dispatch
    assert 'FOLLOW_UP_ALGORITHM_VERSION.equals(algorithmVersion)' in dispatch
    for control in (
        'FOLLOW_UP_NOTICE_COUNT = 2',
        'CONTRIBUTOR_COUNT = 2',
        'CONTOUR_COUNT = 4',
        'DECLARED_POLYGON_VERTICES = 8',
        'POLYGON_COORDINATE_PAIRS = 9',
        'MAXIMUM_NOTICE_CHARACTERS = 1024',
        'MAXIMUM_POLYGON_CHARACTERS = 1024',
        'Set.of("core_info", "contributors", "gm_info", "follow_up_info")',
        'Set.of("MMI", "PGA", "PGV", "polygon")',
        '"short_review"',
        '"wea"',
        '"cm/s/s"',
        '"cm/s"',
    ):
        assert control in parser


def test_follow_up_domain_is_typed_and_transport_independent() -> None:
    hierarchy = (TOOLS / "ShakeAlertMessage.java").read_text(encoding="utf-8")
    assert "sealed interface ShakeAlertMessage" in hierarchy
    assert "permits ShakeAlertEventUpdate, ShakeAlertFollowUp" in hierarchy
    for name in (
        "ShakeAlertFollowUp.java", "FollowUpNotice.java",
        "GroundMotionContour.java", "GeoCoordinate.java",
        "ShakeAlertFollowUpParser.java", "ShakeAlertMessageParser.java",
        "ShakeAlertXmlSupport.java",
    ):
        source = (TOOLS / name).read_text(encoding="utf-8")
        assert "Map<String, Object>" not in source
        assert "javax.jms" not in source
        assert "org.apache.activemq" not in source


def test_follow_up_xml_boundary_is_hardened_and_offline() -> None:
    source = (TOOLS / "ShakeAlertXmlSupport.java").read_text(encoding="utf-8")
    for control in (
        "disallow-doctype-decl", "external-general-entities",
        "external-parameter-entities", "load-external-dtd",
        "ACCESS_EXTERNAL_DTD", "ACCESS_EXTERNAL_SCHEMA",
        "setXIncludeAware(false)", "setExpandEntityReferences(false)",
        "CodingErrorAction.REPORT",
    ):
        assert control in source


def test_receiver_uses_profile_dispatch_after_capture_commit() -> None:
    source = (TOOLS / "ScenarioOpenWireReceiver.java").read_text(encoding="utf-8")
    assert "new ShakeAlertMessageParser(" in source
    capture = source.index("return capture(")
    processing = source.index("processor.process(envelope)", capture)
    assert capture < processing


def test_historical_manifest_is_sanitized_strict_and_frozen() -> None:
    manifest = (ROOT / "src/test/resources/historical-capture-manifest.tsv").read_text()
    lines = manifest.splitlines()
    assert lines[0] == "source\tcapture_id\tpayload_size\tpayload_sha256\tdomain_type\tversion"
    assert len(lines[1:]) == 28
    assert sum("\tShakeAlertEventUpdate\t" in line for line in lines[1:]) == 26
    assert sum("\tShakeAlertFollowUp\t900" in line for line in lines[1:]) == 2
    assert "payload_base64" not in manifest
    assert "password" not in manifest
    assert all(line.startswith(("legacy\t", "persistent\t")) for line in lines[1:])


def test_historical_regression_uses_named_members_not_directory_count() -> None:
    source = (ROOT / "src/test/java/ShakeAlertHistoricalCaptureRegressionTest.java").read_text()
    assert "loadManifest(MANIFEST)" in source
    assert "missing historical capture" in source
    assert "duplicate manifest capture ID" in source
    assert "assertEquals(member.payloadSha256(), envelope.payloadSha256()" in source
    assert "assertEquals(28, files.size())" not in source
