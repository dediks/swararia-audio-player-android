"""Host-only actual protocol, HTTPS and session JVM tests; cached tools, no Gradle."""
from pathlib import Path
import subprocess, xml.etree.ElementTree as ET
import os
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT
OUT=Path(os.environ['TMPDIR'])/'swararia-remote-session-checks'
OUT.mkdir(parents=True, exist_ok=True)
CACHE=BASE/'.cache/gradle/caches/modules-2/files-2.1'
SRC=ROOT/'app/src/main/java/com/swararia/audiolab'
TEST=ROOT/'app/src/test/java/com/swararia/audiolab'
def artifact(g,a,v):
    return next((CACHE/g/a/v).rglob('*.jar'))
compiler=artifact('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21')
annotations=artifact('org.jetbrains','annotations','23.0.0')
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=next((CACHE/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').rglob('*.pom'))
deps=[artifact(d.find('m:groupId',ns).text,d.find('m:artifactId',ns).text,d.find('m:version',ns).text) for d in ET.parse(pom).findall('m:dependencies/m:dependency',ns)]
cp=[artifact('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'), annotations,artifact('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),artifact('com.fasterxml.jackson.core','jackson-core','2.20.1'),artifact('com.fasterxml.jackson.core','jackson-databind','2.20.1'),artifact('com.fasterxml.jackson.core','jackson-annotations','2.20'),BASE/'.toolchain/gradle/lib/junit-4.13.2.jar',BASE/'.toolchain/gradle/lib/hamcrest-core-1.3.jar']
java=str(BASE/'.toolchain/jdk/bin/java')
classes=['DesktopRemoteProtocolTest','DesktopRemoteTlsTest','DesktopRemoteSessionTest','DesktopRemoteFadeOutTest','DesktopRemotePersistenceTest']
jar=OUT/'android-fixes.jar'
cmd=[java,'-Xmx512m','-cp',':'.join(map(str,[compiler,annotations]+deps)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(map(str,cp)),'-d',str(jar)]
cmd += [str(SRC/(s+'.kt')) for s in ['DesktopRemoteProtocol','DesktopRemoteHttpsApi','DesktopRemoteSession','DesktopRemotePersistence']]
cmd += [str(TEST/(s+'.kt')) for s in classes]
result=subprocess.run(cmd,capture_output=True,text=True,timeout=100)
log='COMPILER exit='+str(result.returncode)+'\n'+result.stdout+result.stderr
print(log,flush=True)
if result.returncode: raise SystemExit(result.returncode)
failed = False
for label, tests in [('remote-session-inventory',classes)]:
    result=subprocess.run([java,'-Xmx256m','-cp',':'.join(map(str,[jar]+cp)),'org.junit.runner.JUnitCore']+['com.swararia.audiolab.'+s for s in tests],capture_output=True,text=True,timeout=60)
    text=label+' exit='+str(result.returncode)+'\n'+result.stdout+result.stderr
    failed = failed or result.returncode != 0
    log+=text
    print(text,flush=True)
(OUT/'android-fixes-results.txt').write_text(log)

raise SystemExit(1 if failed else 0)
