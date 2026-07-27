"""Evidence records for endpoint and controlled-connectivity discovery."""
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
class FailureClassification(Enum):
    DNS="dns_failure";ROUTING="routing_failure";FIREWALL="firewall_failure";CERTIFICATE="certificate_failure";HOSTNAME="hostname_mismatch";CLIENT_CERTIFICATE="client_certificate_failure";AUTHENTICATION="authentication_failure";AUTHORIZATION="authorization_failure";SUBSCRIPTION="subscription_failure";PROTOCOL="protocol_mismatch";VERSION="unsupported_protocol_version";ACCOUNT_DISABLED="account_disabled";ALLOW_LIST="ip_not_allow_listed";SERVER_UNAVAILABLE="server_unavailable";UNKNOWN="unknown"
@dataclass(frozen=True,slots=True)
class ConnectivityEvidence:
    endpoint_name:str;observed_utc:datetime;dns_success:bool;tcp_success:bool;tls_success:bool;authentication_success:bool|None
    negotiated_protocol:str|None;tls_version:str|None;certificate_fingerprint_sha256:str|None;failure:FailureClassification|None
@dataclass(frozen=True,slots=True)
class EndpointFacts:
    logical_name:str;environment:str;hostname:str;port:int;protocol:str;protocol_version:str;tls_required:bool
    destination:str;authentication_method:str|None=None;durable_subscription:bool|None=None;heartbeat_seconds:float|None=None
    keepalive_seconds:float|None=None;acknowledgment_mode:str|None=None;maximum_message_bytes:int|None=None
