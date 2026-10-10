#!/usr/bin/env python3
"""Servidor de prueba para las capturas de CI: responde el API de Kairo con los datos de
KairoKit/Tests/KairoCoreTests/Fixtures (anonimizados). Uso: python3 mock_server.py [puerto]"""
import json, os, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FIX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "KairoKit", "Tests", "KairoCoreTests", "Fixtures")

def fixture(name):
    with open(os.path.join(FIX, name + ".json"), encoding="utf-8") as f:
        return json.load(f)

AUTH = fixture("auth")
ROUTES = [
    (r"^/api/me$", lambda: {"user": AUTH["user"]}),
    (r"^/api/today$", lambda: fixture("today")),
    (r"^/api/tasks$", lambda: fixture("tasks")),
    (r"^/api/projects$", lambda: []),
    (r"^/api/events$", lambda: fixture("events")),
    (r"^/api/notes(/search)?$", lambda: fixture("notes")),
    (r"^/api/notes/tags$", lambda: {"tags": []}),
    (r"^/api/lists$", lambda: fixture("lists")),
    (r"^/api/reminders$", lambda: fixture("reminders")),
    (r"^/api/places$", lambda: fixture("places")),
    (r"^/api/memories$", lambda: fixture("memories")),
    (r"^/api/recordings$", lambda: fixture("recordings")),
    (r"^/api/activity$", lambda: fixture("activity")),
    (r"^/api/conversations$", lambda: fixture("conversations")),
    (r"^/api/conversations/[^/]+/messages$", lambda: fixture("messages")),
    (r"^/api/settings$", lambda: fixture("settings")),
    (r"^/api/voice/voices$", lambda: {"voices": [{"id": "ef_dora", "name": "Dora", "description": "Español"}]}),
    (r"^/api/devices$", lambda: fixture("devices")),
    (r"^/api/family$", lambda: fixture("family")),
    (r"^/api/family/invites$", lambda: []),
    (r"^/api/notices$", lambda: fixture("notices")),
    (r"^/api/sync/device$", lambda: fixture("sync")),
    (r"^/api/push/apns$", lambda: {"available": False, "registeredDevices": 0}),
]

class Handler(BaseHTTPRequestHandler):
    def send_json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        for pattern, fn in ROUTES:
            if re.match(pattern, path):
                return self.send_json(fn())
        if path == "/ws":
            return self.send_json({"error": "sin tiempo real en las capturas"}, 404)
        self.send_json([])

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        path = self.path.split("?")[0]
        if path in ("/api/auth/login", "/api/auth/refresh", "/api/auth/pair"):
            return self.send_json(AUTH)
        if path == "/api/auth/pair-code":
            return self.send_json({"code": "482913", "expiresIn": 300})
        self.send_json({})

    do_PATCH = do_POST
    do_DELETE = do_POST

    def log_message(self, fmt, *args):
        sys.stderr.write("mock: " + (fmt % args) + "\n")

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
