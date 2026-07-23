"""System health model placeholders."""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class HealthComponent:
    """Minimal placeholder for component health."""

    name: str
    status: str
    timestamp: datetime | None


@dataclass(frozen=True)
class SystemHealth:
    """Minimal placeholder for system health."""

    algorithm_name: str
    algorithm_version: str
    timestamp: datetime
    components: tuple[HealthComponent, ...]
