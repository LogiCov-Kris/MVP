#!/usr/bin/env python3
"""Loopback-only fake delivery endpoint for authentication browser tests."""
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json
import os

output = Path('target/email-smoke/message.json')
output.parent.mkdir(parents=True, exist_ok=True)

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if self.path != '/emails' or size > 65536:
            self.send_error(400)
            return
        raw = self.rfile.read(size)
        message = BytesParser(policy=default).parsebytes(
            ('Content-Type: ' + self.headers['Content-Type'] + '\r\nMIME-Version: 1.0\r\n\r\n').encode() + raw)
        fields = {part.get_param('name', header='content-disposition'): part.get_content() for part in message.iter_parts()}
        descriptor = os.open(output, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w') as target:
            json.dump(fields, target)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"id":"local-browser-test"}')

    def log_message(self, *args):
        pass

HTTPServer(('127.0.0.1', 18424), Handler).serve_forever()
