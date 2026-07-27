"""Construction-only boundary for the Eclipse Paho MQTT client."""

from __future__ import annotations

import paho.mqtt.client as mqtt


__all__ = ["create_paho_client"]


_PROTOCOLS = {
    "3.1": mqtt.MQTTv31,
    "3.1.1": mqtt.MQTTv311,
}


def create_paho_client(*, protocol_version: str) -> mqtt.Client:
    """Construct an inactive Paho client for a verified MQTT version."""
    if not isinstance(protocol_version, str):
        raise TypeError("protocol_version must be a string")

    try:
        protocol = _PROTOCOLS[protocol_version]
    except KeyError:
        raise ValueError(
            "protocol_version must be '3.1' or '3.1.1'"
        ) from None

    return mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        protocol=protocol,
        reconnect_on_failure=False,
    )
