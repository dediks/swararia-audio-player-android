"""Reproducible offline 0.4 gate. Runs real cached builds/checks, never a phone test."""
from pathlib import Path
from datetime import datetime,timezone
import collections,hashlib,json,subprocess,sys,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'verification';OUT.mkdir(exist_ok=True)
commands=[
 ['bash','build.sh','testDebugUnitTest','--offline'],
 ['bash','build.sh','lintDebug','--offline'],
 ['bash','build.sh','assembleDebug','--offline'],
 ['python','test_architecture.py'],['python','test_search.py'],
 ['python','verification/run_data_checks.py'],['python','verification/run_batch_checks.py'],
 ['python','test_core.py'],['python','test_ui_rules.py'],
 ['python','test_ui_source_contracts.py'],['python','test_ui_integration.py'],
 ['python','test_architecture_wiring.py'],['python','test_upgrade_03.py'],
 ['python','-m','unittest','test_music_metadata_plugin','-v'],
 ['python','test_engine_payload.py'],['python','test_apk_contract.py'],
 ['python','verify_artifacts.py'],['python','verify_update_04.py'],
]
results=[]
for index,command in enumerate(commands,1):
    logfile=OUT/f'upgrade-0.4-gate-{index:02d}.log'
    with logfile.open('w') as stream:
        result=subprocess.run(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,timeout=300)
    entry={'command':command,'exit_code':result.returncode,'log':logfile.relative_to(ROOT).as_posix()}
    results.append(entry)
    print(('PASS' if result.returncode==0 else 'FAIL')+': '+' '.join(command),flush=True)
    if result.returncode:
        (OUT/'checks-0.4.0.json').write_text(json.dumps({'passed':False,'commands':results},indent=2)+'\n')
        sys.exit(result.returncode)
counts=dict(collections.Counter(i.get('severity') for i in ET.parse(ROOT/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')))
unit=json.loads((OUT/'verification.json').read_text())['unit_tests']
assert not counts.get('Error',0) and not counts.get('Fatal',0)
assert unit['tests']>0 and not unit['failures'] and not unit['errors']
paths=sorted(p for p in (ROOT/'app').rglob('*') if p.is_file() and 'build' not in p.relative_to(ROOT/'app').parts and p.suffix in {'.kt','.gradle','.xml','.json'})
source_hash=hashlib.sha256()
for p in paths:source_hash.update(p.relative_to(ROOT).as_posix().encode()+b'\0'+p.read_bytes()+b'\0')
report={'generated_at_utc':datetime.now(timezone.utc).isoformat(),'passed':True,'commands':results,'unit_tests':unit,'lint':counts,
        'app_source_fingerprint_sha256':source_hash.hexdigest(),'offline_builds':True,'device_execution_tested':False,
        'ui_runtime_render_tested':False,'native_ingest_execution_tested':False,'preview_latency_measured_on_device':False}
(OUT/'checks-0.4.0.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:report[k] for k in ['passed','unit_tests','lint','device_execution_tested']},indent=2))
