"""Mandatory in-process safety interlock."""

from __future__ import annotations

import os

LAB_BANNER = "SHAKEALERT LAB — NO OPERATIONAL OUTPUTS"


class SafetyInterlockError(RuntimeError):
    """Raised when passive laboratory mode is not explicitly configured."""


def enforce_safety_interlock(environment: dict[str, str] | None = None) -> None:
    values = os.environ if environment is None else environment
    if values.get("ALLOW_OPERATIONAL_OUTPUTS") != "false":
        raise SafetyInterlockError(
            "ALLOW_OPERATIONAL_OUTPUTS must be present and exactly false"
        )
