"""Loopback-only laboratory status dashboard."""
from __future__ import annotations
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from typing import Callable
BANNER="LABORATORY ONLY — NOT AN OPERATIONAL WARNING SYSTEM"
_LOOPBACK={"127.0.0.1","::1","localhost"}
def create_server(host:str,port:int,status:Callable[[],dict[str,object]])->ThreadingHTTPServer:
    if host not in _LOOPBACK:raise ValueError("dashboard must bind to a loopback address")
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self)->None:
            if self.path=="/health":body=json.dumps({"banner":BANNER,"status":status()},sort_keys=True).encode();content="application/json"
            elif self.path=="/":body=(f"<!doctype html><title>ShakeAlert Lab</title><h1>{BANNER}</h1><pre>"+json.dumps(status(),indent=2,sort_keys=True)+"</pre>").encode();content="text/html; charset=utf-8"
            else:self.send_error(404);return
            self.send_response(200);self.send_header("Content-Type",content);self.send_header("Content-Length",str(len(body)));self.send_header("Cache-Control","no-store");self.end_headers();self.wfile.write(body)
        def log_message(self,format:str,*args:object)->None:return
    return ThreadingHTTPServer((host,port),Handler)
