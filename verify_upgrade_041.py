"""Offline 0.4.1 gate. Independent commands; never import executable test runners."""
from datetime import datetime, timezone
from pathlib import Path
import collections
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
OUT = ROOT/'verification'


def source_fingerprint():
    paths = sorted(p for p in (ROOT/'app').rglob('*') if p.is_file()
                   and 'build' not in p.relative_to(ROOT/'app').parts
                   and p.suffix in {'.kt', '.gradle', '.xml', '.json'})
    result = hashlib.sha256()
    for path in paths:
        result.update(path.relative_to(ROOT).as_posix().encode()+b'\0'+path.read_bytes()+b'\0')
    return result.hexdigest()


def main():
    OUT.mkdir(exist_ok=True)
    commands = [
        ['bash', 'build.sh', 'testDebugUnitTest', '--offline'],
        ['bash', 'build.sh', 'lintDebug', '--offline'],
        ['bash', 'build.sh', 'assembleDebug', '--offline'],
        ['python', 'test_architecture.py'], ['python', 'test_search.py'],
        ['python', 'verification/run_data_checks.py'], ['python', 'verification/run_batch_checks.py'],
        ['python', 'test_core.py'], ['python', 'test_ui_rules.py'],
        ['python', 'test_ui_source_contracts.py'], ['python', 'test_ui_integration.py'],
        ['python', 'test_architecture_wiring.py'], ['python', 'test_upgrade_03.py'],
        ['python', '-m', 'unittest', 'test_music_metadata_plugin', '-v'],
        ['python', 'test_engine_payload.py'], ['python', 'test_apk_contract.py'],
        ['python', '-m', 'unittest', 'verification.test_preview_background', '-v'],
        ['python', 'verification/run_preview_checks.py'],
        ['python', 'verify_artifacts.py'], ['python', 'verify_update_041.py'],
    ]
    results = []
    started_fingerprint = source_fingerprint()
    report = {'passed': False, 'commands': results}
    for index, command in enumerate(commands, 1):
        logfile = OUT/f'upgrade-0.4.1-gate-{index:02d}.log'
        with logfile.open('w') as stream:
            try:
                result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=420)
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
        results.append({'command': command, 'exit_code': code, 'log': logfile.relative_to(ROOT).as_posix()})
        print(('PASS' if code == 0 else 'FAIL')+': '+' '.join(command), flush=True)
        if code:
            (OUT/'checks-0.4.1.json').write_text(json.dumps(report, indent=2)+'\n')
            return code
    counts = dict(collections.Counter(i.get('severity') for i in ET.parse(ROOT/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')))
    artifact = json.loads((OUT/'verification.json').read_text())
    unit = artifact['unit_tests']
    assert not counts.get('Error', 0) and not counts.get('Fatal', 0)
    assert unit['tests'] > 0 and not unit['failures'] and not unit['errors'] and not unit['skipped']
    assert source_fingerprint() == started_fingerprint, 'App source changed during checks; rerun gate'
    report.update({'generated_at_utc': datetime.now(timezone.utc).isoformat(), 'passed': True,
                   'unit_tests': unit, 'lint': counts, 'app_source_fingerprint_sha256': started_fingerprint,
                   'artifact_sha256': {a['file']: a['sha256'] for a in artifact['artifacts']},
                   'offline_builds': True, 'device_execution_tested': False, 'ui_runtime_render_tested': False,
                   'native_ingest_execution_tested': False, 'preview_latency_measured_on_device': False})
    (OUT/'checks-0.4.1.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k: report[k] for k in ['passed', 'unit_tests', 'lint', 'device_execution_tested']}, indent=2))
    print(f'PASS: {len(results)} independent offline gates')
    return 0


if __name__ == '__main__':
    sys.exit(main())
