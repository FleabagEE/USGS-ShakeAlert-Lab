"""Fail-closed laboratory command line."""
import argparse,shutil,sys,time
from pathlib import Path
from shakealert_lab.config import ConfigurationError,load_config
from shakealert_lab.credentials import CredentialError,inspect_credentials
from shakealert_lab.logging_setup import configure_logging
from shakealert_lab.metrics import Metrics
from shakealert_lab.observability import BANNER,create_server
from shakealert_lab.observability.status import ConnectionStatus,LaboratoryStatus
from shakealert_lab.safety import SafetyInterlockError,enforce_safety_interlock
from shakealert_lab.transport.registry import TransportRegistry,UnknownTransportError
def _parser()->argparse.ArgumentParser:
    parser=argparse.ArgumentParser(prog="shakealert-lab");sub=parser.add_subparsers(dest="command",required=True)
    for name in ("validate-config","credential-status","receiver"):
        item=sub.add_parser(name);item.add_argument("--config",type=Path,required=True)
    dashboard=sub.add_parser("dashboard");dashboard.add_argument("--host",default="127.0.0.1");dashboard.add_argument("--port",type=int,default=8765);return parser
def main(argv:list[str]|None=None)->int:
    try:enforce_safety_interlock()
    except SafetyInterlockError as error:print(f"FATAL: {error}",file=sys.stderr);return 78
    configure_logging();args=_parser().parse_args(argv)
    try:
        if args.command in ("validate-config","credential-status","receiver"):config=load_config(args.config)
        if args.command=="validate-config":print("configuration: valid");return 0
        if args.command=="credential-status":
            for line in inspect_credentials(config.endpoint.credentials).display_lines():print(line)
            return 0
        if args.command=="receiver":
            if not config.endpoint.connect_authorized:print("FATAL: endpoint connection is not explicitly authorized",file=sys.stderr);return 77
            try:TransportRegistry().create(config,None) # type: ignore[arg-type]
            except UnknownTransportError as error:print(f"FATAL: {error}",file=sys.stderr);return 69
        if args.command=="dashboard":
            metrics=Metrics();started=time.monotonic()
            def status()->dict[str,object]:
                usage=shutil.disk_usage("/");return LaboratoryStatus(ConnectionStatus("production", "production"), ConnectionStatus("scenario", "scenario"), None, usage.free, round(time.monotonic()-started, 3)).to_dict()
            server=create_server(args.host,args.port,status);print(BANNER);server.serve_forever();return 0
    except (ConfigurationError,CredentialError,OSError,ValueError) as error:print(f"FATAL: {error}",file=sys.stderr);return 78
    return 64
