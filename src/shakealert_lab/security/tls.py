"""Strict TLS context construction; no connection behavior."""
from pathlib import Path
import ssl
def create_verified_context(*,ca_file:Path|None=None,client_certificate:Path|None=None,private_key:Path|None=None)->ssl.SSLContext:
    context=ssl.create_default_context(cafile=None if ca_file is None else str(ca_file));context.check_hostname=True;context.verify_mode=ssl.CERT_REQUIRED;context.minimum_version=ssl.TLSVersion.TLSv1_2
    if (client_certificate is None)!=(private_key is None):raise ValueError("client certificate and private key must be configured together")
    if client_certificate is not None:context.load_cert_chain(str(client_certificate),str(private_key))
    return context
