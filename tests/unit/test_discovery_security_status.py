from pathlib import Path
import ssl
import pytest
from shakealert_lab.discovery import FailureClassification
from shakealert_lab.observability.status import ConnectionStatus,LaboratoryStatus
from shakealert_lab.parsing.registry import ParserRegistry,UnknownSchemaError
from shakealert_lab.security.tls import create_verified_context
def test_parser_registry_has_no_default()->None:
    with pytest.raises(UnknownSchemaError):ParserRegistry().parse(b"x",content_type="unknown",schema_identifier="unknown")
def test_tls_context_is_strict()->None:
    context=create_verified_context();assert context.check_hostname and context.verify_mode==ssl.CERT_REQUIRED and context.minimum_version>=ssl.TLSVersion.TLSv1_2
    with pytest.raises(ValueError):create_verified_context(client_certificate=Path("cert"))
def test_dashboard_status_contains_both_isolated_streams()->None:
    status=LaboratoryStatus(ConnectionStatus("production","production"),ConnectionStatus("scenario","scenario"),None,1,2).to_dict()
    assert status["production"]["environment"]=="production" and status["scenario"]["environment"]=="scenario"
def test_failure_taxonomy_is_complete()->None:
    assert {x.value for x in FailureClassification}>={"dns_failure","authentication_failure","ip_not_allow_listed","unknown"}
