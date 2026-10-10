#!/usr/bin/env python3
"""Mock server for the CI screenshots: answers the Kairo API with the sample data of "The Rivera family"
(see demo_data.py), always relative to the current time. Usage: python3 mock_server.py [port]"""
import json
import re
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import demo_data


def demo():
    return demo_data.Demo(datetime.now(timezone.utc))


ROUTES = [
    (r"^/api/me$", lambda d: {"user": d.user()}),
    (r"^/api/today$", lambda d: d.today_response()),
    (r"^/api/tasks$", lambda d: d.tasks()),
    (r"^/api/projects$", lambda d: []),
    (r"^/api/events$", lambda d: d.events()),
    (r"^/api/notes(/search)?$", lambda d: d.notes()),
    (r"^/api/notes/tags$", lambda d: {"tags": ["home", "gifts"]}),
    (r"^/api/lists$", lambda d: d.lists()),
    (r"^/api/reminders$", lambda d: d.reminders()),
    (r"^/api/places$", lambda d: d.places()),
    (r"^/api/memories$", lambda d: d.memories()),
    (r"^/api/recordings$", lambda d: d.recordings()),
    (r"^/api/activity$", lambda d: d.activity()),
    (r"^/api/conversations$", lambda d: d.conversations()),
    (r"^/api/conversations/[^/]+/messages$", lambda d: d.messages()),
    (r"^/api/settings$", lambda d: d.settings()),
    (r"^/api/voice/voices$", lambda d: d.voices()),
    (r"^/api/devices$", lambda d: d.devices()),
    (r"^/api/family$", lambda d: d.family()),
    (r"^/api/family/invites$", lambda d: []),
    (r"^/api/notices$", lambda d: d.notices_response()),
    (r"^/api/sync/device$", lambda d: d.sync()),
    (r"^/api/push/apns$", lambda d: {"available": False, "registeredDevices": 0}),
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
                return self.send_json(fn(demo()))
        if path == "/ws":
            return self.send_json({"error": "no realtime in the screenshots"}, 404)
        self.send_json([])

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        path = self.path.split("?")[0]
        if path in ("/api/auth/login", "/api/auth/refresh", "/api/auth/pair"):
            return self.send_json(demo().auth())
        if path == "/api/auth/pair-code":
            return self.send_json({"code": "482913", "expiresIn": 300})
        if path == "/api/briefing":
            return self.send_json(demo().briefing())
        self.send_json({})

    do_PATCH = do_POST
    do_DELETE = do_POST

    def log_message(self, fmt, *args):
        sys.stderr.write("mock: " + (fmt % args) + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
