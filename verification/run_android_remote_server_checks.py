"""Cached JVM checks of actual server engine, HTTP listener and TLS identity.
No Gradle/downloads. Network fixtures and listener bind inputs must be 127.0.0.1.
Not Android controller/service, MediaStore, Compose or device acceptance.
"""
from pathlib import Path
import os
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/gradle/caches/modules-2/files-2.1'
OUT = Path(os.environ['TMPDIR']) / 'swararia-android-server-checks'
OUT.mkdir(parents=True, exist_ok=True)
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'
TEST = ROOT / 'app/src/test/java/com/swararia/audiolab'
classes = ['AndroidRemoteServerEngineTest', 'AndroidRemoteTlsIdentityTest', 'AndroidRemoteHttpLoopbackTest']
fixture = (TEST / 'AndroidRemoteHttpLoopbackTest.kt').read_text()
# Fail closed if the approved fixtures gain a nonloopback listener or client.
assert re.findall(r'bindIp\s*=\s*"([^"]+)"', fixture) == ['127.0.0.1', '127.0.0.1']
assert 'createSocket("127.0.0.1", port)' in fixture
listener = (SRC / 'AndroidRemoteHttpListener.kt').read_text()
assert 'createServerSocket(requestedPort, TOTAL_SLOTS, inet)' in listener
assert 'InetAddress.getByAddress(bindIp, ipBytes)' in listener

def artifact(group, name, version):
    return next((CACHE / group / name / version).rglob('*.jar'))

compiler = artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21')
annotations = artifact('org.jetbrains', 'annotations', '23.0.0')
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = next((CACHE / 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').rglob('*.pom'))
deps = [artifact(d.find('m:groupId', ns).text, d.find('m:artifactId', ns).text,
                 d.find('m:version', ns).text)
        for d in ET.parse(pom).findall('m:dependencies/m:dependency', ns)]
cp = [artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'), annotations,
      artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
      artifact('com.fasterxml.jackson.core', 'jackson-core', '2.20.1'),
      artifact('com.fasterxml.jackson.core', 'jackson-databind', '2.20.1'),
      artifact('com.fasterxml.jackson.core', 'jackson-annotations', '2.20'),
      ROOT / '.toolchain/gradle/lib/junit-4.13.2.jar',
      ROOT / '.toolchain/gradle/lib/hamcrest-core-1.3.jar']
java = str(ROOT / '.toolchain/jdk/bin/java')
jar = OUT / 'android-server.jar'
sources = ['DesktopRemoteProtocol', 'DesktopRemoteHttpsApi', 'LibraryRules', 'UiPresentation',
           'AndroidRemoteServerEngine', 'AndroidRemoteTlsIdentity', 'AndroidRemoteHttpListener']
cmd = [java, '-Xmx512m', '-cp', ':'.join(map(str, [compiler, annotations] + deps)),
       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
       '-jvm-target', '17', '-classpath', ':'.join(map(str, cp)), '-d', str(jar)]
cmd += [str(SRC / (s + '.kt')) for s in sources]
cmd += [str(TEST / (s + '.kt')) for s in classes]
result = subprocess.run(cmd, capture_output=True, text=True, timeout=100, cwd=ROOT)
log = 'COMPILER exit=' + str(result.returncode) + '\n' + result.stdout + result.stderr
print(log, flush=True)
if result.returncode:
    (OUT / 'results.txt').write_text(log)
    raise SystemExit(result.returncode)
failed = False
for test in classes:
    result = subprocess.run([java, '-Xmx256m', '-Djava.io.tmpdir=' + str(OUT), '-cp',
                             ':'.join(map(str, [jar] + cp)), 'org.junit.runner.JUnitCore',
                             'com.swararia.audiolab.' + test],
                            capture_output=True, text=True, timeout=60, cwd=ROOT)
    text = test + ' exit=' + str(result.returncode) + '\n' + result.stdout + result.stderr
    log += text
    print(text, flush=True)
    failed |= result.returncode != 0
(OUT / 'results.txt').write_text(log)
raise SystemExit(1 if failed else 0)
