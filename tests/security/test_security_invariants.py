import ast
from pathlib import Path
def test_no_network_or_output_calls_in_storage_validation_and_normalization()->None:
    root=Path(__file__).parents[2]/"src"/"shakealert_lab";forbidden={"publish","connect","connect_async","subscribe","system","popen"}
    for relative in ("storage/capture.py","validation.py","normalization.py","classifier.py"):
        tree=ast.parse((root/relative).read_text());calls={n.func.attr for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute)}
        assert calls.isdisjoint(forbidden)
def test_service_units_are_hardened_and_separate()->None:
    root=Path(__file__).parents[2];scenario=(root/"services/shakealert-scenario-receiver.service").read_text();production=(root/"services/shakealert-production-receiver.service").read_text()
    for text in (scenario,production):
        assert "User=shakealert" in text and "NoNewPrivileges=true" in text and "ProtectSystem=strict" in text
    assert "/scenario/" in scenario and "/production/" in production
