"""Real JVM tests for search data/rules using the existing pinned local toolchain."""
from pathlib import Path
import os, subprocess, tempfile, xml.etree.ElementTree as ET
import sys, json, time
from datetime import datetime, timezone
ROOT=Path(__file__).resolve().parent
CACHE=ROOT/'.cache/gradle/caches/modules-2/files-2.1'
def artifact(group,name,version):
    paths=list((CACHE/group/name/version).glob(f'*/{name}-{version}.jar'))
    if len(paths)!=1:raise SystemExit(f'Missing/ambiguous existing dependency: {group}:{name}:{version}')
    return str(paths[0])
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=next((CACHE/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').glob('*/*.pom'))
deps=[artifact(d.find('m:groupId',ns).text,d.find('m:artifactId',ns).text,d.find('m:version',ns).text) for d in ET.parse(pom).findall('m:dependencies/m:dependency',ns)]
annotations=artifact('org.jetbrains','annotations','23.0.0')
cp=os.pathsep.join([artifact('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),artifact('junit','junit','4.13.2'),artifact('org.hamcrest','hamcrest-core','1.3'),annotations,artifact('com.fasterxml.jackson.core','jackson-databind','2.20.1'),artifact('com.fasterxml.jackson.core','jackson-core','2.20.1'),artifact('com.fasterxml.jackson.core','jackson-annotations','2.20')])
java=str(ROOT/'.toolchain/jdk/bin/java')
main=ROOT/'app/src/main/java/com/swararia/audiolab'
tests=ROOT/'app/src/test/java/com/swararia/audiolab'
sources=[main/n for n in ['YoutubeSearchData.kt','ThumbnailPolicy.kt'] if (main/n).exists()]
test_sources=[tests/n for n in ['YoutubeSearchTest.kt','YoutubeSearchRulesTest.kt','ThumbnailPolicyTest.kt'] if (tests/n).exists()]
utilities=[tests/'SearchMetadataProbe.kt'] if (tests/'SearchMetadataProbe.kt').is_file() else []
assert test_sources
with tempfile.TemporaryDirectory(prefix='search-jvm-',dir=ROOT/'.cache/tmp') as out:
    compile=[java,'-Xmx256m','-cp',os.pathsep.join([artifact('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),annotations]+deps),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',out]+[str(p) for p in sources+test_sources+utilities]
    subprocess.run(compile,check=True)
    jvm=[java,'-Xmx128m','-cp',out+os.pathsep+cp]
    result=subprocess.call(jvm+['org.junit.runner.JUnitCore']+['com.swararia.audiolab.'+p.stem for p in test_sources])
    if result or '--live' not in sys.argv:raise SystemExit(result)
    live_dir=ROOT/'.cache/search-probe';live_dir.mkdir(exist_ok=True)
    reports=[]
    for provider in ['YOUTUBE','YOUTUBE_MUSIC']:
        args=json.loads(subprocess.check_output(jvm+['com.swararia.audiolab.SearchMetadataProbe','arguments',provider,'royalty free music'],text=True))
        started=time.monotonic()
        record={'provider':provider,'arguments_from_kotlin_rules':True}
        try:
            process=subprocess.run([sys.executable,str(ROOT/'app/src/main/res/raw/ytdlp')]+args,capture_output=True,text=True,timeout=35,cwd=live_dir)
            record.update({'exit_code':process.returncode,'stderr':process.stderr[-1800:]})
            if process.returncode==0:
                raw=live_dir/(provider.lower()+'.json');raw.write_text(process.stdout)
                parsed=subprocess.run(jvm+['com.swararia.audiolab.SearchMetadataProbe','parse',str(raw)],capture_output=True,text=True,timeout=15)
                record.update({'kotlin_parser_exit_code':parsed.returncode,'parsed_results':json.loads(parsed.stdout) if parsed.returncode==0 else None,'parser_error':parsed.stderr[-1200:]})
        except subprocess.TimeoutExpired:record['timed_out']=True
        record['elapsed_seconds']=round(time.monotonic()-started,2);reports.append(record)
    summary={'checked_at_utc':datetime.now(timezone.utc).isoformat(),'host_only':True,'android_device_tested':False,'media_downloaded':False,'results':reports}
    (ROOT/'verification/search-e2e-live-0.2.0.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))
    raise SystemExit(0 if all(x.get('kotlin_parser_exit_code')==0 for x in reports) else 1)
