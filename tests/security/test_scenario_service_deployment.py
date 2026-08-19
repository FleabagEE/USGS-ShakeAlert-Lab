import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[2]
UNIT = ROOT / "services" / "shakealert-scenario-receiver.service"
UNIT_TEXT = UNIT.read_text(encoding="utf-8")
SERVICE = (ROOT / "tools" / "ScenarioReceiverService.java").read_text(encoding="utf-8")


def test_systemd_unit_syntax_is_accepted(tmp_path: Path) -> None:
    installed = tmp_path / "shakealert-scenario-receiver.service"
    installed.write_text(UNIT_TEXT.replace("/opt/quakelogic/shakealert-lab", str(ROOT)))
    result = subprocess.run(
        ["systemd-analyze", "verify", str(installed)], text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    assert result.returncode == 0, result.stderr
    assert "Unknown key" not in result.stderr


def test_unit_is_one_foreground_java_process_without_pid_file() -> None:
    assert "Type=simple" in UNIT_TEXT
    assert UNIT_TEXT.count("ExecStart=") == 1
    assert "bin/java-receiver run --subscribe" in UNIT_TEXT
    assert "PIDFile=" not in UNIT_TEXT
    for forbidden in ("nohup", "daemon", "--fork", " &", "python3"):
        assert forbidden not in UNIT_TEXT


def test_unit_is_scenario_only_without_failover_publish_or_restart() -> None:
    assert "scenario.eew.shakealert.org 61612" in UNIT_TEXT
    assert "eew.test_QuakeLogic-SA1.dm.data" in UNIT_TEXT
    assert "Restart=no" in UNIT_TEXT
    for forbidden in ("production", "61617", "failover:", "createproducer", "restart=on"):
        assert forbidden not in UNIT_TEXT.lower()


def test_runtime_state_permissions_and_path_allowlist() -> None:
    for required in (
        "RuntimeDirectory=shakealert-scenario-receiver",
        "RuntimeDirectoryMode=0750",
        "StateDirectory=shakealert-scenario-receiver",
        "StateDirectoryMode=0750",
        "UMask=0027",
        "ReadOnlyPaths=/opt/quakelogic/shakealert-lab/credentials/scenario/QuakeLogic-SA1",
        "ReadWritePaths=/run/shakealert-scenario-receiver /var/lib/shakealert-scenario-receiver",
    ):
        assert required in UNIT_TEXT


def test_sigterm_requests_coordinator_shutdown_and_timeout_ordering() -> None:
    assert "KillSignal=SIGTERM" in UNIT_TEXT
    assert "TimeoutStopSec=45s" in UNIT_TEXT
    assert "SendSIGKILL=no" in UNIT_TEXT
    process = (ROOT / "tools" / "ScenarioReceiverProcessLifecycle.java").read_text(encoding="utf-8")
    receiver = (ROOT / "tools" / "ScenarioOpenWireReceiver.java").read_text(encoding="utf-8")
    assert "Signal.handle(termSignal, ignored -> service.requestShutdown())" in process
    assert "service.awaitShutdownRequest()" in process
    assert "service.stop(shutdownDeadline)" in process
    assert "coordinatorComplete.await" in process
    assert "System.exit" not in process
    assert "Runtime.halt" not in process
    assert "Duration.ofSeconds(30), Duration.ofSeconds(35)" in receiver


def test_hardening_has_documented_compatible_controls() -> None:
    for setting in (
        "NoNewPrivileges=true", "ProtectSystem=strict", "ProtectHome=true",
        "PrivateTmp=true", "PrivateDevices=true", "CapabilityBoundingSet=",
        "RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6",
    ):
        assert setting in UNIT_TEXT
    deployment = (ROOT / "docs" / "scenario-service-deployment.md").read_text()
    for exception in ("MemoryDenyWriteExecute", "PrivateUsers", "IP allowlisting"):
        assert exception in deployment


def test_local_readiness_script_truth_table(tmp_path: Path) -> None:
    script = ROOT / "bin" / "scenario-receiver-status"
    base = {
        "lifecycle_state": "RUNNING", "connected": True, "authenticated": True,
        "subscribed": True, "connection_started": True,
        "async_jms_error": False, "parser_failed": False,
    }
    health = tmp_path / "health.json"
    health.write_text(json.dumps(base))
    assert subprocess.run([script, health], capture_output=True, text=True).stdout == "READY=yes\n"
    for key in ("connected", "authenticated", "subscribed", "connection_started"):
        value = dict(base); value[key] = False; health.write_text(json.dumps(value))
        result = subprocess.run([script, health], capture_output=True, text=True)
        assert result.returncode == 1 and result.stdout == "READY=no\n"
    for key in ("async_jms_error", "parser_failed"):
        value = dict(base); value[key] = True; health.write_text(json.dumps(value))
        result = subprocess.run([script, health], capture_output=True, text=True)
        assert result.returncode == 1 and result.stdout == "READY=no\n"


def test_health_and_rejection_sources_exclude_secret_and_payload_fields() -> None:
    health = (ROOT / "tools" / "LocalHealthStatus.java").read_text()
    rejection = (ROOT / "tools" / "SanitizedRejectionStore.java").read_text()
    incident = (ROOT / "tools" / "SanitizedAsyncJmsIncidentStore.java").read_text()
    for forbidden in ("credentialPath", "password", "payloadBase64", "getText()",
                      "getObjectProperty", "rawException", "printStackTrace"):
        assert forbidden not in health + rejection + incident


def test_async_incident_is_bounded_atomic_and_persistent() -> None:
    incident = (ROOT / "tools" / "SanitizedAsyncJmsIncidentStore.java").read_text()
    receiver = (ROOT / "tools" / "ScenarioOpenWireReceiver.java").read_text()
    for required in (
        "MAX_RECORD_BYTES = 4096", "channel.force(true)",
        "StandardCopyOption.ATOMIC_MOVE", "StandardCopyOption.REPLACE_EXISTING",
        "Files.setPosixFilePermissions", 'resolve("incidents")',
    ):
        assert required in incident + receiver
    assert "RuntimeDirectory" not in receiver


def test_duplicate_persistence_is_explicitly_disabled() -> None:
    processor = (ROOT / "tools" / "ShakeAlertEventProcessor.java").read_text()
    deployment = (ROOT / "docs" / "scenario-service-deployment.md").read_text()
    assert "HashSet" in processor
    assert "activation-local" in deployment
    assert "old redelivery may be processed again" in deployment
    for persistent_api in ("FileChannel", "DataSource", "jdbc:", "sqlite"):
        assert persistent_api not in processor
