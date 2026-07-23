"""Import checks for the Phase 3.1 architecture skeleton."""

import importlib

import pytest


@pytest.mark.parametrize(
    "module_name",
    (
        "shakealert_lab",
        "shakealert_lab.__main__",
        "shakealert_lab.composition",
        "shakealert_lab.config",
        "shakealert_lab.transport",
        "shakealert_lab.transport.base",
        "shakealert_lab.transport.mqtt",
        "shakealert_lab.messaging",
        "shakealert_lab.messaging.inbound",
        "shakealert_lab.messaging.router",
        "shakealert_lab.messaging.queue_worker",
        "shakealert_lab.parsing",
        "shakealert_lab.parsing.event",
        "shakealert_lab.parsing.health",
        "shakealert_lab.parsing.errors",
        "shakealert_lab.models",
        "shakealert_lab.models.event",
        "shakealert_lab.models.health",
        "shakealert_lab.storage",
        "shakealert_lab.storage.raw_message_store",
        "shakealert_lab.runtime",
        "shakealert_lab.runtime.service",
    ),
)
def test_architecture_module_imports(module_name: str) -> None:
    importlib.import_module(module_name)
