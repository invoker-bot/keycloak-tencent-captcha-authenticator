"""Disposable TLS Tencent API fixture; never shipped in the provider or examples."""
import hashlib
import hmac
import json
import ssl
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

used = set()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        data = json.loads(body)
        timestamp = self.headers['X-TC-Timestamp']
        date = datetime.fromtimestamp(int(timestamp), timezone.utc).strftime('%Y-%m-%d')
        scope = date + '/captcha/tc3_request'
        canonical = ('POST\n/\n\ncontent-type:application/json; charset=utf-8\n'
                     'host:captcha.tencentcloudapi.com\n\ncontent-type;host\n' + hashlib.sha256(body).hexdigest())
        signing = 'TC3-HMAC-SHA256\n' + timestamp + '\n' + scope + '\n' + hashlib.sha256(canonical.encode()).hexdigest()
        key = b'TC3synthetic-tencent-secret-key'
        for value in (date, 'captcha', 'tc3_request'):
            key = hmac.new(key, value.encode(), hashlib.sha256).digest()
        signature = hmac.new(key, signing.encode(), hashlib.sha256).hexdigest()
        expected = ('TC3-HMAC-SHA256 Credential=synthetic-tencent-secret-id/' + scope +
                    ', SignedHeaders=content-type;host, Signature=' + signature)
        signed = (hmac.compare_digest(self.headers['Authorization'], expected)
                  and self.headers['X-TC-Action'] == 'DescribeCaptchaResult'
                  and data.get('CaptchaAppId') == 123456789
                  and data.get('AppSecretKey') == 'synthetic-captcha-app-secret-key'
                  and data.get('CaptchaType') == 9 and bool(data.get('UserIp')))
        ticket = data.get('Ticket', '')
        accepted = (signed and ticket.startswith('integration-valid-') and ticket not in used
                    and data.get('Randstr') == 'integration-randstr')
        if accepted:
            used.add(ticket)
        result = json.dumps({'Response': {'CaptchaCode': 1 if accepted else 9}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(result)))
        self.end_headers()
        self.wfile.write(result)
        print(json.dumps({'signed': signed, 'accepted': accepted}), flush=True)


server = HTTPServer(('0.0.0.0', 443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('/fixture/server.pem', '/fixture/server.key')
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
