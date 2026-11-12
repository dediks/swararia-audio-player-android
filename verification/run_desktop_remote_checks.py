"""Compile real Kotlin transport/protocol and exercise real Desktop HTTPS on loopback.
Uses existing cached Kotlin/JDK/Jackson/Coroutines and desktop Python; installs nothing.
This is JVM wire evidence, not Android-device/Windows/physical-audio acceptance.
"""
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/gradle/caches/modules-2/files-2.1'
OUT = Path(os.environ['TMPDIR']) / 'swararia-desktop-remote-wire'
OUT.mkdir(parents=True, exist_ok=True)
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'
DESKTOP = Path(os.environ.get('SWARARIA_DESKTOP_REPO', str(ROOT.parent / 'swararia-desktop')))
PYTHON = Path(os.environ.get('SWARARIA_DESKTOP_PYTHON', str(DESKTOP / '.venv/bin/python')))
if not (DESKTOP / 'secure_remote.py').is_file() or not PYTHON.is_file():
    raise SystemExit('Desktop source/test Python unavailable; set SWARARIA_DESKTOP_REPO/PYTHON explicitly.')


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
      artifact('com.fasterxml.jackson.core', 'jackson-annotations', '2.20')]
java = str(ROOT / '.toolchain/jdk/bin/java')
jar = OUT / 'wire-probe.jar'
subprocess.run([java, '-Xmx512m', '-cp', ':'.join(map(str, [compiler, annotations] + deps)),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-jvm-target', '17', '-classpath', ':'.join(map(str, cp)), '-d', str(jar),
                str(SRC / 'DesktopRemoteProtocol.kt'), str(SRC / 'DesktopRemoteHttpsApi.kt'),
                str(ROOT / 'verification/DesktopRemoteWireProbe.kt')], check=True, cwd=ROOT, timeout=90)
subprocess.run([java, '-Xmx256m', '-Djava.io.tmpdir=' + str(OUT), '-cp',
                ':'.join(map(str, [jar] + cp)), 'com.swararia.audiolab.DesktopRemoteWireProbe',
                str(PYTHON), str(ROOT / 'verification/desktop_remote_fixture.py'), str(DESKTOP)],
               check=True, cwd=ROOT, timeout=70)
print('PASS: real Kotlin transport + real Desktop loopback HTTPS (not a device/audio test)')
