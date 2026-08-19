from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).parents[2]
SOURCE = (ROOT / "tools" / "ScenarioOpenWireReceiver.java").read_text()
SERVICE_SOURCE = (ROOT / "tools" / "ScenarioReceiverService.java").read_text()
ASYNC_CLASSIFIER = (ROOT / "tools" / "AsyncJmsFailureClassifier.java").read_text()
WRAPPER = (ROOT / "bin" / "java-receiver").read_text()


def test_maven_versions_are_repository_pinned() -> None:
    root = ET.parse(ROOT / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    properties = root.find("m:properties", ns)
    assert properties is not None
    assert properties.find("m:maven.compiler.release", ns).text == "21"
    assert properties.find("m:activemq.version", ns).text == "5.19.10"
    assert properties.find("m:log4j.version", ns).text == "2.26.1"


def test_wrapper_is_offline_and_rejects_uncontrolled_classpaths() -> None:
    assert "mvn -o" in WRAPPER
    assert "refusing to download" in WRAPPER
    assert "/tmp|/tmp/*" in WRAPPER
    assert "*/backups*/*" in WRAPPER
    assert "*/Downloads/*" in WRAPPER
    assert "unapproved ActiveMQ version" in WRAPPER
    assert "missing, duplicate, or mixed ActiveMQ client versions" in WRAPPER


def test_authentication_public_api_is_account_only() -> None:
    assert 'args.length == 2 && "--authenticate-only".equals(args[0])' in SOURCE
    assert "authenticateOnly(endpoint, args[1]);" in SOURCE
    assert "credential-directory" not in SOURCE
    assert "credential-root" not in SOURCE
    assert 'Path.of("credentials", "scenario")' in SOURCE


def test_credentials_are_fail_closed_and_account_scoped() -> None:
    for required in (
        "toAbsolutePath().normalize()",
        "toRealPath()",
        "startsWith(rootReal)",
        "Files.isSymbolicLink",
        "credential directory mode is not 0700",
        "credential artifact mode is not 0600",
        "EXPECTED_CREDENTIAL_OWNER",
        "MessageDigest.isEqual(actual, expected)",
        'directoryReal.resolve("username")',
        'directoryReal.resolve("password")',
    ):
        assert required in SOURCE


def test_consumer_is_exact_nondurable_topic_without_selector_or_no_local() -> None:
    assert "createdSession.createTopic(exactDestination)" in SERVICE_SOURCE
    assert "createdSession.createConsumer(topic, null, false)" in SERVICE_SOURCE
    assert "createQueue(" not in SOURCE + SERVICE_SOURCE
    assert "createDurable" not in SOURCE + SERVICE_SOURCE
    assert "setClientID" not in SOURCE + SERVICE_SOURCE
    expected = "eew" + chr(92) * 2 + ".test_[A-Za-z0-9][A-Za-z0-9-]{0,63}" + chr(92) * 2 + ".dm" + chr(92) * 2 + ".data"
    assert expected in SOURCE


def test_client_acknowledgement_is_explicit_and_transport_ack_mode_is_unchanged() -> None:
    assert "Session.CLIENT_ACKNOWLEDGE" in SERVICE_SOURCE
    assert "Session.AUTO_ACKNOWLEDGE" not in SERVICE_SOURCE
    assert "message.acknowledge()" in SERVICE_SOURCE
    assert "setSendAcksAsync" not in SOURCE + SERVICE_SOURCE
    callback = SERVICE_SOURCE[SERVICE_SOURCE.index("NativeCaptureCommit committed") : SERVICE_SOURCE.index("HealthSnapshot snapshot()") ]
    assert callback.index("captureHandler.capture") < callback.index("message.acknowledge()")
    assert callback.index("message.acknowledge()") < callback.index("postAcknowledgeHandler.process")


def test_no_retry_fallback_publishing_or_production_path() -> None:
    for forbidden in (
        "createProducer",
        "MessageProducer",
        "failover:",
        "reconnect",
        "production.eew",
    ):
        assert forbidden not in SOURCE + SERVICE_SOURCE


def test_async_failure_classifier_is_type_only_bounded_and_fail_closed() -> None:
    assert "MAX_CAUSE_NODES = 8" in ASYNC_CLASSIFIER
    assert "IdentityHashMap" in ASYNC_CLASSIFIER
    assert "UNKNOWN_JMS_FAILURE" in ASYNC_CLASSIFIER
    assert "getLinkedException" in ASYNC_CLASSIFIER
    for forbidden in ("getMessage()", "toString()", "printStackTrace", "getStackTrace"):
        assert forbidden not in ASYNC_CLASSIFIER
    for event in ('"ASYNC_EXCEPTION"', '"FAILED"'):
        assert event in SERVICE_SOURCE


def test_authentication_and_callback_lifecycle_ordering() -> None:
    helper = SOURCE[SOURCE.index("static AuthenticatedSession establishAuthenticatedSession") : SOURCE.index("static MessageConsumer createPassiveTopicConsumer")]
    assert helper.index("connection.createSession") < helper.index('events.accept("AUTHENTICATED")')
    callback_start = SOURCE.index("(message, generation) -> {")
    callback = SOURCE[callback_start : SOURCE.index("healthStatus == null", callback_start)]
    assert callback.index("beforePayloadValidation") < callback.index("return capture(")
    service_callback = SERVICE_SOURCE[
        SERVICE_SOURCE.index("createdConsumer.setMessageListener") :
        SERVICE_SOURCE.index("createdConnection.start()")
    ]
    assert "acceptCallback(activationGeneration, message)" in service_callback
    for state in (
        "STARTING", "CONNECTING", "AUTHENTICATING", "SUBSCRIBED",
        "RUNNING", "STOPPING", "STOPPED", "FAILED",
    ):
        assert state in SERVICE_SOURCE
    assert '"MESSAGE_CALLBACK"' in SOURCE
    for event in ("CAPTURE_COMMITTED", "ACKNOWLEDGEMENT_STARTED",
                  "ACKNOWLEDGED", "ACKNOWLEDGEMENT_FAILED"):
        assert f'"{event}"' in SERVICE_SOURCE
    for event in ("SHUTDOWN_REQUESTED", "CALLBACK_ADMISSION_CLOSED",
                  "CONSUMER_CLOSED", "CALLBACK_DRAIN_COMPLETE", "SESSION_CLOSED",
                  "CONNECTION_CLOSED", "INSTANCE_LOCK_RELEASED"):
        assert f'"{event}"' in SERVICE_SOURCE
    assert "next == LifecycleState.STOPPING || next == LifecycleState.STOPPED" in SERVICE_SOURCE
