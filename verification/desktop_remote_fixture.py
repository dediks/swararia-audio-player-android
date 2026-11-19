"""Host-only wire fixture: real desktop API, loopback only, no physical audio.
Fault modes change only this test process, never desktop source/configuration.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time

sys.path.insert(0, sys.argv[1])
if (Path(sys.argv[1]) / "swararia" / "remote" / "secure_remote.py").is_file():
    from swararia.core import fs_browser, transfer
    from swararia.remote.secure_remote import RemoteError, SecureRemote
    import swararia.remote.secure_remote_tls as transport
    from swararia.server.hub import RemotePlayerHub
    media_resolver = fs_browser.resolve_safe_audio_file
    sync_scanner = transfer.scan_sync_folders
else:
    from secure_remote import RemoteError, SecureRemote
    import secure_remote_tls as transport
    from web_ui import RemotePlayerHub
    media_resolver = None
    sync_scanner = None

control = {"slow": False, "drop": False, "role": "control", "posts": 0, "capability_headers": 0, "wrong_headers": 0}
base_handler = transport.NativeHandler


class FixtureHandler(base_handler):
    def _dispatch(self):
        header = self.headers.get("X-Swararia-Capabilities")
        if self.command == "GET" and self.path == "/v1/capabilities":
            if header == "extended-v1":
                control["capability_headers"] += 1
            else:
                control["wrong_headers"] += 1
        elif self.path.startswith("/v1/sync/") or self.path == "/v1/library/upload":
            if header != "extended-v1":
                control["wrong_headers"] += 1
        elif header is not None:
            control["wrong_headers"] += 1
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
import shutil
import subprocess

fixture_dir = Path(tempfile.mkdtemp(prefix="swararia-wire-inbox-", dir="/tmp" if Path("/tmp").is_dir() else None))
setlist_dir = fixture_dir / "Show Malam"
setlist_dir.mkdir(parents=True, exist_ok=True)
sample_track = fixture_dir / "Test Track + Live.mp3"
sample_bytes = (b"ID3\x04\x00wire-mp3-frame-" * 128)
sample_sha = hashlib.sha256(sample_bytes).hexdigest()
sample_track.write_bytes(sample_bytes)
playlist_track = setlist_dir / "Lagu Sync.mp3"
if shutil.which("ffmpeg"):
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1", "-c:a", "libmp3lame", "-b:a", "128k", str(playlist_track)],
        check=True,
    )
    playlist_bytes = playlist_track.read_bytes()
else:
    playlist_bytes = (b"ID3\x04\x00playlist-mp3-frame-" * 128)
    playlist_track.write_bytes(playlist_bytes)
playlist_sha = hashlib.sha256(playlist_bytes).hexdigest()
(setlist_dir / "RawLossless.wav").write_bytes(b"RIFF\x24\x00\x00\x00WAVEfmt ")

if media_resolver is None:
    def media_resolver(_src, rel, _def):
        cand = (fixture_dir / rel).resolve()
        if cand.is_relative_to(fixture_dir.resolve()) and cand.is_file():
            return cand, "audio/mpeg"
        raise FileNotFoundError(rel)


def fixture_upload_handler(rfile, content_length, dest_root, filename, sha256_hex, playlist_name):
    if sync_scanner is not None:
        try:
            committed = transfer.stage_and_commit_stream(
                rfile,
                content_length,
                dest_root,
                filename,
                sha256_hex,
                subfolder=playlist_name,
                default_inbox=fixture_dir,
            )
        except transfer.TransferValidationError as exc:
            raise RemoteError(exc.code, exc.status) from None
        committed.pop("_saved_path", None)
        return (200 if committed.get("deduplicated") else 201), committed
    raise RemoteError("upload_disabled", 403)


remote = SecureRemote(
    RemotePlayerHub(),
    media_resolver=media_resolver,
    default_inbox=fixture_dir,
    sync_scanner=sync_scanner,
    upload_handler=fixture_upload_handler if sync_scanner is not None else None,
)
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
            }, acknowledgements, "wire-fixture-master-instance", catalog={
                "source_dir": str(fixture_dir),
                "playlist": "Semua Lagu",
                "active_group_id": "g_all",
                "total_tracks": 1,
                "groups": [{"id": "g_all", "name": "Semua Lagu", "count": 1}],
                "items": [{"id": "trk_1", "title": "Test Track + Live.mp3", "group_id": "g_all", "rel_path": sample_track.name}],
            })
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
        elif command == "MEDIA_ON":
            remote.set_media_transfer(True)
        elif command == "MEDIA_OFF":
            remote.set_media_transfer(False)
        elif command == "SYNC_ON":
            if hasattr(remote, "set_sync_enabled"):
                remote.set_sync_enabled(True, source_dir=str(fixture_dir), source_label="wire-fixture")
        elif command == "SYNC_OFF":
            if hasattr(remote, "set_sync_enabled"):
                remote.set_sync_enabled(False)
        elif command == "UPLOAD_ON":
            if hasattr(remote, "set_upload_enabled"):
                remote.set_upload_enabled(True, dest_dir=str(fixture_dir), dest_label="wire-fixture")
        elif command == "UPLOAD_OFF":
            if hasattr(remote, "set_upload_enabled"):
                remote.set_upload_enabled(False)
        elif command == "MEDIA_META":
            print(json.dumps({"track_id": "trk_1", "filename": sample_track.name, "size_bytes": len(sample_bytes), "sha256": sample_sha}), flush=True)
            continue
        elif command == "SYNC_META":
            fid = transfer.make_sync_folder_id("Show Malam") if sync_scanner is not None else ""
            print(json.dumps({"folder_id": fid, "playlist_sha": playlist_sha, "playlist_size": len(playlist_bytes)}), flush=True)
            continue
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
            print(json.dumps({"command_posts": control["posts"], "capability_headers": control["capability_headers"], "wrong_headers": control["wrong_headers"]}), flush=True)
            continue
        print("OK", flush=True)
finally:
    stop.set()
    remote.stop()
    if "thread" in globals():
        thread.join(timeout=2)
    if "path" in globals() and path.exists():
        path.unlink()
    if "fixture_dir" in globals() and fixture_dir.exists():
        shutil.rmtree(fixture_dir, ignore_errors=True)
