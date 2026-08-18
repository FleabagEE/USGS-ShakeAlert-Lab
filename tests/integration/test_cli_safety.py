from pathlib import Path
from shakealert_lab.cli import main
def test_cli_refuses_absent_interlock(monkeypatch)->None:
    monkeypatch.delenv("ALLOW_OPERATIONAL_OUTPUTS",raising=False);assert main(["dashboard"])==78
def test_authoritative_scenario_config_validates_but_connection_stays_gated(monkeypatch)->None:
    monkeypatch.setenv("ALLOW_OPERATIONAL_OUTPUTS","false");path=Path(__file__).parents[2]/"config/scenario/receiver.toml.template"
    assert main(["validate-config","--config",str(path)])==0
    assert main(["receiver","--config",str(path)])==77
