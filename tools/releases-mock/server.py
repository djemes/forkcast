"""A stand-in for GitHub's Releases API, to test the in-app updater on the emulator.

Usage (from the project folder):
  python tools/releases-mock/server.py path/to/newer.apk [--port 8767] [--tag v1.5.0-beta.1]
  adb -s emulator-5554 reverse tcp:8767 tcp:8767
  adb -s emulator-5554 shell "run-as app.forkcast sh -c 'echo http://127.0.0.1:8767 > files/releases-mock'"

Only a debuggable build reads files/releases-mock (ReleasesMock). Modes, switched with
GET /mode/<name>: ok, badsum (SHA256SUMS doesn't match), nosum (no SHA256SUMS, no digest),
ratelimit (403 with no requests left), private (404, as a private repo without a token).
"""
import argparse
import hashlib
import http.server
import json
import os

parser = argparse.ArgumentParser()
parser.add_argument("apk")
parser.add_argument("--port", type=int, default=8767)
parser.add_argument("--tag", default="v1.5.0-beta.1")
args = parser.parse_args()

APK = open(args.apk, "rb").read()
NAME = f"Forkcast-{args.tag}.apk"
SHA = hashlib.sha256(APK).hexdigest()
state = {"mode": "ok"}


class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, code, body=b"", ctype="application/json", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def release(self):
        base = f"http://127.0.0.1:{args.port}"
        mode = state["mode"]
        assets = [{"name": NAME, "url": f"{base}/a/apk", "size": len(APK), "digest": None if mode == "nosum" else f"sha256:{SHA}"}]
        if mode != "nosum":
            assets.append({"name": "SHA256SUMS", "url": f"{base}/a/sums", "size": len(self.sums())})
        return {
            "tag_name": args.tag, "name": f"Forkcast {args.tag[1:]}", "html_url": "https://github.com/djemes/forkcast/releases",
            "body": "**Test release**\n- Served by tools/releases-mock", "draft": False, "prerelease": "-" in args.tag, "assets": assets,
        }

    def sums(self):
        sha = "0" * 64 if state["mode"] == "badsum" else SHA
        return f"{sha}  {NAME}\n".encode()

    def do_GET(self):
        path = self.path.split("?")[0]
        if path.startswith("/mode/"):
            state["mode"] = path.removeprefix("/mode/")
            return self._send(200, state["mode"].encode(), "text/plain")
        if state["mode"] == "ratelimit" and path.startswith("/repos/"):
            return self._send(403, b"{}", headers={"x-ratelimit-remaining": "0", "x-ratelimit-reset": "1793000000"})
        if state["mode"] == "private" and path.startswith("/repos/"):
            return self._send(404, b"{}")
        if path.endswith("/releases/latest"):
            # GitHub's "latest" skips pre-releases.
            return self._send(404, b"{}") if "-" in args.tag else self._send(200, json.dumps(self.release()).encode())
        if path.endswith("/releases"):
            return self._send(200, json.dumps([self.release()]).encode())
        if path in ("/a/apk", "/a/sums"):
            return self._send(302, headers={"Location": f"http://127.0.0.1:{args.port}/s/{path[3:]}"})
        if path == "/s/apk":
            return self._send(200, APK, "application/vnd.android.package-archive")
        if path == "/s/sums":
            return self._send(200, self.sums(), "application/octet-stream")
        self._send(404, b"{}")


print(f"Serving {NAME} ({len(APK)} bytes, sha256 {SHA[:12]}…) on port {args.port}")
http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
