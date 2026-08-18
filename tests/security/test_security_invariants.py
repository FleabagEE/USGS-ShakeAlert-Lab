import ast
import re
from pathlib import Path
def test_no_network_or_output_calls_in_storage_validation_and_normalization()->None:
    root=Path(__file__).parents[2]/"src"/"shakealert_lab";forbidden={"publish","connect","connect_async","subscribe","system","popen"}
    for relative in ("storage/capture.py","validation.py","normalization.py","classifier.py"):
        tree=ast.parse((root/relative).read_text());calls={n.func.attr for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute)}
        assert calls.isdisjoint(forbidden)
def test_openwire_receiver_has_separate_lifecycle_and_no_publish_path()->None:
    root=Path(__file__).parents[2];source=(root/"tools"/"ScenarioOpenWireReceiver.java").read_text()
    assert "AUTHORIZED_SCENARIO_PORT = 61612" in source
    assert "61617" not in source
    assert "socket.verifyHostName=true" in source
    assert "--authenticate-only" in source and "--subscribe" in source
    assert "SESSION=created" in source
    assert "createProducer" not in source and "MessageProducer" not in source
    assert "EXACT_EVENT_TOPIC" in source and "maximumPayloadBytes" in source
    assert 'Pattern.compile("QuakeLogic-SA1")' in source

def test_openwire_credentials_are_passed_in_username_password_order()->None:
    root=Path(__file__).parents[2];source=(root/"tools"/"ScenarioOpenWireReceiver.java").read_text()
    factories=re.findall(
        r"(?:new ActiveMQConnectionFactory|connectionFactory)\(\s*new String\((\w+)\),\s*new String\((\w+)\),\s*brokerUrl\(endpoint\)\)",
        source,
    )
    assert factories == [("username", "password"), ("username", "password")]

def test_authentication_success_requires_session_creation()->None:
    root=Path(__file__).parents[2];source=(root/"tools"/"ScenarioOpenWireReceiver.java").read_text()
    authenticate_only=source[source.index("private static void authenticateOnly("):source.index("private static void run(")]
    lifecycle=authenticate_only.index("AuthenticatedSession authenticated = establishAuthenticatedSession(")
    success=authenticate_only.index('System.out.println("AUTHENTICATION=success");')
    assert lifecycle < success
    helper=source[source.index("static AuthenticatedSession establishAuthenticatedSession"):source.index("static MessageConsumer createPassiveTopicConsumer")]
    assert helper.index("supplier.create()") < helper.index("connection.createSession") < helper.index('events.accept("AUTHENTICATED")')
    assert 'System.out.println("SESSION=created");' in authenticate_only
    assert "SESSION=not_created" not in authenticate_only

def test_scenario_template_uses_exact_portal_topic_and_keeps_connection_gated()->None:
    root=Path(__file__).parents[2];template=(root/"config"/"scenario"/"receiver.toml.template").read_text()
    assert 'host="scenario.eew.shakealert.org"' in template and "port=61612" in template
    assert "port=61617" not in template
    assert 'destination="eew.test_QuakeLogic-SA1.dm.data"' in template
    assert "connect_authorized=false" in template
    assert "QuakeLogic-SA1/username" in template and "QuakeLogic-SA1/password" in template

def test_service_units_are_hardened_and_separate()->None:
    root=Path(__file__).parents[2];scenario=(root/"services/shakealert-scenario-receiver.service").read_text();production=(root/"services/shakealert-production-receiver.service").read_text()
    assert "User=quakelogic" in scenario
    assert "User=shakealert" in production
    for text in (scenario, production):
        assert "NoNewPrivileges=true" in text and "ProtectSystem=strict" in text
    assert "/scenario/" in scenario and "/production/" in production
    assert "Restart=no" in scenario
