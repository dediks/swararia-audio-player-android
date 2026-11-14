"""Prepare two standard ZIP split volumes for this chat, without new tools.
The original signed APK is never modified. Verify reconstructed archive and APK.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import math
import shutil
import uuid
import zipfile
import sys

ROOT = Path(__file__).resolve().parent
DIST = ROOT / 'dist'
VERSION = sys.argv[1] if len(sys.argv) > 1 else '0.1.0-spike'
assert VERSION in {'0.1.0-spike', '0.2.0-spike', '0.3.0-spike', '0.4.0-spike', '0.4.1-spike', '0.5.0-spike', '0.5.1-spike', '0.6.0-spike'}, 'Unsupported release version'
APK = DIST / f'Swararia-Audio-Lab-{VERSION}-arm64-v8a.apk'
ARCHIVE = APK.with_suffix('.zip')
PART_BYTES = 48_000_000
BOT_SAFE_LIMIT = 50_000_000

def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

manifest_name = 'manifest.json' if VERSION == '0.1.0-spike' else f"manifest-{VERSION.removesuffix('-spike')}.json"
manifest = json.loads((DIST / manifest_name).read_text())
expected = next(item for item in manifest['artifacts'] if item['file'] == APK.name)
assert APK.is_file() and APK.stat().st_size == expected['bytes']
assert sha256(APK) == expected['sha256']
assert not ARCHIVE.exists(), 'Refusing to replace an existing archive'
assert not list(DIST.glob(ARCHIVE.name + '.00*')), 'Split volumes already exist'
assert shutil.disk_usage(DIST).free > 4 * APK.stat().st_size

with zipfile.ZipFile(ARCHIVE, mode='x', compression=zipfile.ZIP_STORED) as archive:
    archive.write(APK, arcname=APK.name)

count = math.ceil(ARCHIVE.stat().st_size / PART_BYTES)
assert count == 2, f'Approved scope is exactly two volumes, got {count}'
parts = []
with ARCHIVE.open('rb') as source:
    for number in range(1, count + 1):
        path = DIST / f'{ARCHIVE.name}.{number:03d}'
        payload = source.read(PART_BYTES)
        assert payload
        with path.open('xb') as output:
            output.write(payload)
        assert path.stat().st_size < BOT_SAFE_LIMIT
        parts.append({'part': number, 'file': path.name, 'bytes': path.stat().st_size,
                      'sha256': sha256(path)})
    assert source.read(1) == b''
assert len(parts) == 2
assert sum(part['bytes'] for part in parts) == ARCHIVE.stat().st_size

check_dir = ROOT / '.cache' / 'telegram-transfer-check' / uuid.uuid4().hex
check_dir.mkdir(parents=True, exist_ok=False)
joined = check_dir / ARCHIVE.name
try:
    with joined.open('xb') as destination:
        for part in parts:
            with (DIST / part['file']).open('rb') as source:
                shutil.copyfileobj(source, destination, length=1024 * 1024)
    assert sha256(joined) == sha256(ARCHIVE), 'Joined archive differs'
    with zipfile.ZipFile(joined) as archive:
        assert archive.namelist() == [APK.name]
        info = archive.getinfo(APK.name)
        assert info.file_size == APK.stat().st_size
        with archive.open(info) as stream:
            extracted_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
        assert extracted_sha == expected['sha256'], 'Extracted APK differs'
    assert sha256(APK) == expected['sha256'], 'Original APK changed'
finally:
    if joined.exists():
        joined.unlink()
    check_dir.rmdir()

result = {
    'prepared_at_utc': datetime.now(timezone.utc).isoformat(),
    'format': 'Standard ZIP_STORED archive, raw .zip.001/.zip.002 split volumes',
    'parts': parts,
    'archive_bytes': ARCHIVE.stat().st_size,
    'archive_sha256': sha256(ARCHIVE),
    'apk_file': APK.name,
    'apk_bytes': APK.stat().st_size,
    'apk_sha256': expected['sha256'],
    'rejoined_archive_verified': True,
    'extracted_apk_identical_to_original': True,
    'new_tools_installed': False,
    'external_host_upload_performed': False,
    'delivery_status': 'Prepared for attachment dispatch to the current Telegram chat',
    'phone_tested': False,
    'known_limit': 'Online ingest currently requires 4KB memory pages; 16KB ingest is unsupported.'
}
parts_manifest = 'telegram-parts.json' if VERSION == '0.1.0-spike' else f"telegram-parts-{VERSION.removesuffix('-spike')}.json"
with (DIST / parts_manifest).open('x') as output:
    json.dump(result, output, indent=2)
    output.write('\n')
print(json.dumps(result, indent=2))
