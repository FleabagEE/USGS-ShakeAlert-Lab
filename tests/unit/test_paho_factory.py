"""Tests for the construction-only Eclipse Paho client factory."""

import ast
from inspect import signature
from pathlib import Path

import pytest

import shakealert_lab.transport.paho_factory as paho_factory


PROHIBITED_METHODS = {
    "connect",
    "connect_async",
    "reconnect",
    "disconnect",
    "loop_start",
    "loop_stop",
    "subscribe",
    "username_pw_set",
    "tls_set",
    "tls_set_context",
    "enable_logger",
}

PROHIBITED_PARAMETERS = {
    "host",
    "hostname",
    "port",
    "username",
    "password",
    "credential",
    "credentials",
    "tls",
    "certificate",
    "certificates",
    "topic",
    "topics",
    "subscription",
    "subscriptions",
    "keepalive",
    "logger",
    "logging",
}


class ConstructorSpy:
    """Record construction without creating a real native client."""

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []
        self.client = object()

    def __call__(self, **kwargs: object) -> object:
        self.calls.append(kwargs)
        return self.client


def test_factory_returns_an_inactive_paho_client() -> None:
    client = paho_factory.create_paho_client(
        protocol_version="3.1.1"
    )

    assert isinstance(client, paho_factory.mqtt.Client)
    assert client.is_connected() is False
    assert client.socket() is None


@pytest.mark.parametrize(
    ("protocol_version", "expected_protocol"),
    (
        ("3.1", paho_factory.mqtt.MQTTv31),
        ("3.1.1", paho_factory.mqtt.MQTTv311),
    ),
)
def test_factory_selects_exact_callback_api_and_protocol(
    monkeypatch: pytest.MonkeyPatch,
    protocol_version: str,
    expected_protocol: object,
) -> None:
    constructor = ConstructorSpy()
    monkeypatch.setattr(paho_factory.mqtt, "Client", constructor)

    client = paho_factory.create_paho_client(
        protocol_version=protocol_version
    )

    assert client is constructor.client
    assert constructor.calls == [
        {
            "callback_api_version": (
                paho_factory.mqtt.CallbackAPIVersion.VERSION2
            ),
            "protocol": expected_protocol,
            "reconnect_on_failure": False,
        }
    ]


@pytest.mark.parametrize(
    "protocol_version",
    ("", "3.0", "3.1.0", "5", "5.0", " 3.1", "3.1.1 "),
)
def test_unsupported_version_is_rejected_before_construction(
    monkeypatch: pytest.MonkeyPatch,
    protocol_version: str,
) -> None:
    constructor = ConstructorSpy()
    monkeypatch.setattr(paho_factory.mqtt, "Client", constructor)

    with pytest.raises(ValueError, match="protocol_version"):
        paho_factory.create_paho_client(
            protocol_version=protocol_version
        )

    assert constructor.calls == []


def test_non_string_version_is_rejected_before_construction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    constructor = ConstructorSpy()
    monkeypatch.setattr(paho_factory.mqtt, "Client", constructor)

    with pytest.raises(TypeError, match="protocol_version"):
        paho_factory.create_paho_client(
            protocol_version=object(),  # type: ignore[arg-type]
        )

    assert constructor.calls == []


def test_factory_signature_contains_only_protocol_version() -> None:
    parameters = signature(
        paho_factory.create_paho_client
    ).parameters

    assert list(parameters) == ["protocol_version"]
    assert parameters["protocol_version"].kind.name == "KEYWORD_ONLY"
    assert PROHIBITED_PARAMETERS.isdisjoint(parameters)


def test_factory_source_contains_no_prohibited_method_calls() -> None:
    source_path = Path(paho_factory.__file__)
    syntax_tree = ast.parse(source_path.read_text(encoding="utf-8"))
    called_attributes = {
        node.func.attr
        for node in ast.walk(syntax_tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
    }

    assert called_attributes.isdisjoint(PROHIBITED_METHODS)


def test_paho_import_is_confined_to_concrete_factory() -> None:
    source_root = Path(paho_factory.__file__).parents[2]
    importing_modules: list[Path] = []

    for source_path in source_root.rglob("*.py"):
        syntax_tree = ast.parse(source_path.read_text(encoding="utf-8"))
        for node in ast.walk(syntax_tree):
            imported_names: list[str] = []
            if isinstance(node, ast.Import):
                imported_names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                imported_names = [node.module or ""]

            if any(
                name == "paho" or name.startswith("paho.")
                for name in imported_names
            ):
                importing_modules.append(source_path)
                break

    assert importing_modules == [Path(paho_factory.__file__)]
