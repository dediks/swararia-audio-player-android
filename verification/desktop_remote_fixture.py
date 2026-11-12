"""Host-only wire fixture: real desktop API, loopback only, no physical audio.
Fault modes change only this test process, never desktop source/configuration.
"""
import base64
import json
import os
from pathlib import Path
import sys
import threading
import time

sys.path.insert(0, sys.argv[1])
from secure_remote import SecureRemote
import secure_remote_tls as transport
from web_ui import RemotePlayerHub

control = {"slow": False, "drop": False, "role": "control", "posts": 0}
base_handler = transport.NativeHandler


class FixtureHandler(base_handler):
    def _dispatch(self):
        if self.command == "POST" and self.path == "/v1/commands":
            control["posts"] += 1
        return super()._dispatch()

    def reply(self, status, data):
        if self.command == "POST" and self.path == "/v1/commands" and control["drop"]:
            control["drop"] = False
            self.close_connection = True
            self.connection.close()
            return
        if self.path == "/v1/state" and control["slow"] and status == 200:
            body = json.dumps(data, allow_nan=False).encode()
            self.close_connection = True
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            for byte in body:
                self.wfile.write(bytes([byte]))
                self.wfile.flush()
                time.sleep(0.2)
            return
        return super().reply(status, data)


transport.NativeHandler = FixtureHandler
# Only the fault-injection fixture relaxes the server's normal six-second budget.
# Otherwise the desktop watchdog would hide a missing client deadline.
transport.NativeTLSServer.CONNECTION_TTL = 60
remote = SecureRemote(RemotePlayerHub())
stop = threading.Event()


def simulated_master():
    acknowledgements = []
    while not stop.wait(0.1):
        with remote.lock:
            for device in list(remote.devices.values()):
                if device["status"] == "pending":
                    remote.approve(device["device_id"], control["role"])
            delivered = remote.master_poll({
                "track": {"name": "Wire fixture.wav"}, "is_playing": False,
                "transport_state": "CUED", "current_time": 0, "duration": 10,
            }, acknowledgements, "wire-fixture-master-instance")
            # No audio engine is running. Reject, never fabricate an applied ack.
            acknowledgements = [{"command_id": c["native_id"], "status": "rejected"}
                                for c in delivered if c.get("native_id")]


try:
    remote.start("127.0.0.1", 0)
    info = remote.admin_status()
    data = {"endpoint": info["url"].removeprefix("https://"),
            "fingerprint": base64.urlsafe_b64encode(bytes.fromhex(info["fingerprint_sha256"])).decode().rstrip("="),
            "invite": remote.open_invite()}
    path = Path(sys.argv[2])
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        json.dump(data, stream)
    thread = threading.Thread(target=simulated_master, daemon=True)
    thread.start()
    print("READY", flush=True)
    for line in sys.stdin:
        command = line.strip()
        if command == "STOP":
            break
        if command == "SLOW_ON":
            control["slow"] = True
        elif command == "SLOW_OFF":
            control["slow"] = False
        elif command == "DROP_NEXT_COMMAND":
            control["drop"] = True
        elif command == "ROLE_VIEW":
            control["role"] = "view_only"
            with remote.lock:
                for d in list(remote.devices.values()):
                    if d["status"] == "active":
                        remote.approve(d["device_id"], "view_only")
        elif command == "REVOKE":
            with remote.lock:
                for d in list(remote.devices.values()):
                    remote.revoke(d["device_id"])
        elif command == "STATS":
            print(json.dumps({"command_posts": control["posts"]}), flush=True)
            continue
        print("OK", flush=True)
finally:
    stop.set()
    remote.stop()
    if "thread" in globals():
        thread.join(timeout=2)
    if "path" in globals() and path.exists():
        path.unlink()
