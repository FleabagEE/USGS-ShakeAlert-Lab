"""Conservative structured-log redaction."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

_SECRET_MARKERS = ("password", "secret", "token", "private_key", "credential")


def redact_mapping(values: Mapping[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in values.items():
        lowered = key.lower()
        if any(marker in lowered for marker in _SECRET_MARKERS):
            result[key] = "<redacted>"
        elif isinstance(value, Mapping):
            result[key] = redact_mapping(value)
        elif isinstance(value, bytes):
            result[key] = f"<bytes:{len(value)}>"
        else:
            result[key] = value
    return result
