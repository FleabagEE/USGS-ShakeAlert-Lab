"""Fail-closed, protocol-neutral laboratory configuration."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import tomllib
import re
from typing import Any, Mapping

from shakealert_lab.messaging.inbound import Environment


SCENARIO_OPENWIRE_HOST = "scenario.eew.shakealert.org"
SCENARIO_OPENWIRE_PORT = 61612
SCENARIO_OPENWIRE_TOPIC = re.compile(r"eew\.test_[A-Za-z0-9][A-Za-z0-9-]{0,63}\.dm\.data")
SCENARIO_ACCOUNT = "QuakeLogic-SA1"


class ConfigurationError(ValueError):
    """Raised when laboratory configuration is incomplete or unsafe."""


def _required_string(data: Mapping[str, Any], name: str) -> str:
    value = data.get(name)
    if not isinstance(value, str) or not value or value != value.strip() or value.startswith("REQUIRED_FROM_"):
        raise ConfigurationError(f"{name} must be a non-empty trimmed string")
    return value


def _optional_string(data: Mapping[str, Any], name: str) -> str | None:
    value = data.get(name)
    if value is None:
        return None
    if not isinstance(value, str) or not value or value != value.strip():
        raise ConfigurationError(f"{name} must be a non-empty trimmed string")
    return value


@dataclass(frozen=True, slots=True)
class CredentialPaths:
    """References to protected files; values are never loaded into config."""

    username: Path | None = None
    password: Path | None = None
    client_certificate: Path | None = None
    private_key: Path | None = None
    ca_certificate: Path | None = None


@dataclass(frozen=True, slots=True)
class EndpointConfig:
    """Verified endpoint facts with no protocol-specific defaults."""

    name: str
    environment: Environment
    host: str
    port: int
    protocol: str
    protocol_version: str
    destination: str
    tls_required: bool
    credentials: CredentialPaths
    maximum_payload_bytes: int
    connect_authorized: bool = False


@dataclass(frozen=True, slots=True)
class LabConfig:
    """Complete configuration for one isolated receiver instance."""

    endpoint: EndpointConfig
    native_directory: Path
    normalized_directory: Path
    rejected_directory: Path
    log_directory: Path
    queue_capacity: int
    shutdown_timeout_seconds: float


def _validate_scenario_endpoint(endpoint: EndpointConfig) -> None:
    """Require the independently configured authoritative passive Scenario boundary."""
    if endpoint.host != SCENARIO_OPENWIRE_HOST or endpoint.port != SCENARIO_OPENWIRE_PORT:
        raise ConfigurationError(
            f"scenario endpoint must be {SCENARIO_OPENWIRE_HOST}:{SCENARIO_OPENWIRE_PORT}"
        )
    if endpoint.protocol != "openwire" or endpoint.protocol_version != "12":
        raise ConfigurationError("scenario protocol must be OpenWire version 12")
    if not endpoint.tls_required:
        raise ConfigurationError("scenario endpoint requires TLS")
    if endpoint.maximum_payload_bytes > 16777216:
        raise ConfigurationError("scenario maximum_payload_bytes exceeds the capture bound")
    if SCENARIO_OPENWIRE_TOPIC.fullmatch(endpoint.destination) is None:
        raise ConfigurationError("scenario destination must be one exact non-wildcard Event topic")
    for name, path in (("username", endpoint.credentials.username), ("password", endpoint.credentials.password)):
        if path is None or path.name != name or path.parent.name != SCENARIO_ACCOUNT:
            raise ConfigurationError(f"scenario {name} credential must be scoped to {SCENARIO_ACCOUNT}")


def _path(base: Path, data: Mapping[str, Any], name: str) -> Path | None:
    value = _optional_string(data, name)
    if value is None:
        return None
    candidate = Path(value)
    return candidate if candidate.is_absolute() else base / candidate


def load_config(path: Path) -> LabConfig:
    """Load a TOML file without reading any referenced secret value."""
    if not isinstance(path, Path):
        raise TypeError("path must be a Path")
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as error:
        raise ConfigurationError("configuration could not be loaded") from error

    endpoint = data.get("endpoint")
    storage = data.get("storage")
    runtime = data.get("runtime")
    credentials = data.get("credentials", {})
    if not all(isinstance(item, Mapping) for item in (endpoint, storage, runtime, credentials)):
        raise ConfigurationError("endpoint, storage, runtime, and credentials must be tables")

    environment_text = _required_string(endpoint, "environment")
    try:
        environment = Environment(environment_text)
    except ValueError:
        raise ConfigurationError("environment must be production or scenario") from None
    if environment is Environment.UNKNOWN:
        raise ConfigurationError("configured endpoint environment cannot be unknown")

    port = endpoint.get("port")
    maximum = endpoint.get("maximum_payload_bytes")
    queue_capacity = runtime.get("queue_capacity")
    timeout = runtime.get("shutdown_timeout_seconds")
    if type(port) is not int or not 1 <= port <= 65535:
        raise ConfigurationError("port must be an integer from 1 through 65535")
    if type(maximum) is not int or maximum <= 0:
        raise ConfigurationError("maximum_payload_bytes must be a positive integer")
    if type(queue_capacity) is not int or queue_capacity <= 0:
        raise ConfigurationError("queue_capacity must be a positive integer")
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or timeout <= 0:
        raise ConfigurationError("shutdown_timeout_seconds must be positive")
    authorized = endpoint.get("connect_authorized", False)
    if type(authorized) is not bool:
        raise ConfigurationError("connect_authorized must be boolean")
    tls_required = endpoint.get("tls_required")
    if type(tls_required) is not bool:
        raise ConfigurationError("tls_required must be boolean")

    base = path.parent
    credential_paths = CredentialPaths(
        username=_path(base, credentials, "username_file"),
        password=_path(base, credentials, "password_file"),
        client_certificate=_path(base, credentials, "client_certificate_file"),
        private_key=_path(base, credentials, "private_key_file"),
        ca_certificate=_path(base, credentials, "ca_certificate_file"),
    )
    required_paths = {}
    for name in ("native_directory", "normalized_directory", "rejected_directory", "log_directory"):
        value = _path(base, storage, name)
        if value is None:
            raise ConfigurationError(f"{name} is required")
        required_paths[name] = value

    endpoint_config = EndpointConfig(
            name=_required_string(endpoint, "name"),
            environment=environment,
            host=_required_string(endpoint, "host"),
            port=port,
            protocol=_required_string(endpoint, "protocol"),
            protocol_version=_required_string(endpoint, "protocol_version"),
            destination=_required_string(endpoint, "destination"),
            tls_required=tls_required,
            credentials=credential_paths,
            maximum_payload_bytes=maximum,
            connect_authorized=authorized,
        )
    if endpoint_config.environment is Environment.SCENARIO:
        _validate_scenario_endpoint(endpoint_config)

    return LabConfig(
        endpoint=endpoint_config,
        native_directory=required_paths["native_directory"],
        normalized_directory=required_paths["normalized_directory"],
        rejected_directory=required_paths["rejected_directory"],
        log_directory=required_paths["log_directory"],
        queue_capacity=queue_capacity,
        shutdown_timeout_seconds=float(timeout),
    )
