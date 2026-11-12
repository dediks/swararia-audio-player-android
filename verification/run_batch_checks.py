"""Compile real queue + runner and execute host JVM tests; never runs Gradle."""
from pathlib import Path
import os
import subprocess
import zipfile
ROOT = Path(__file__).resolve().parents[1]
BASE = Path(os.environ.get('SWARARIA_CACHED_TOOLCHAIN_ROOT', '/home/ubuntu/projects/audio-library-android-spike'))
CACHE = BASE / '.cache/gradle/caches/modules-2/files-2.1'
OUT = Path(os.environ['TMPDIR']) / 'swararia-batch-checks'
OUT.mkdir(parents=True, exist_ok=True)
JAVA = str(BASE / '.toolchain/jdk/bin/java')
LIB = BASE / '.toolchain/gradle/lib'
cp = [BASE / '.toolchain/android-sdk/platforms/android-36/android.jar', LIB / 'junit-4.13.2.jar', LIB / 'hamcrest-core-1.3.jar']
for group, artifact, version in [('androidx.room','room-common-jvm','2.8.4'), ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'), ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'), ('org.jetbrains','annotations','23.0.0')]:
    cp += list((CACHE / group / artifact / version).rglob('*.jar'))
# Data layer compiled by its owner: no substitute repository or fabricated data implementation.
cp += [Path(os.environ['TMPDIR']) / 'swararia-data-checks/data.jar']
compiler = next((CACHE / 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').rglob('*.jar'))
launch = [compiler] + list(LIB.glob('trove4j-*.jar')) + list(LIB.glob('annotations-*.jar')) + list(LIB.glob('kotlin-reflect-*.jar')) + [p for p in cp if 'kotlin-stdlib' in str(p) or 'coroutines' in str(p)]
source = ROOT / 'app/src/main/java/com/swararia/audiolab/BatchDownloadRunner.kt'
tests = ROOT / 'app/src/test/java/com/swararia/audiolab/BatchDownloadRunnerTest.kt'
sources = ROOT / 'app/src/main/java/com/swararia/audiolab'
contracts = [sources / (name + '.kt') for name in ['DownloadQueueContracts', 'DownloadJob', 'DownloadRequest']]
subprocess.run([JAVA, '-cp', ':'.join(map(str, launch)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, cp)), '-d', str(OUT / 'tests.jar'), str(source), str(tests), str(sources / 'DownloadQueuePublication.kt'), str(tests.with_name('DownloadPublicationTest.kt'))] + list(map(str, contracts)), check=True)
# Compile the Android/native adapter against REAL cached AndroidX/yt-dlp/FFmpeg APIs.
# App wiring symbols only are compile-time declarations (not executable behavior evidence).
for artifact, version, group in [('core','1.17.0','androidx.core'), ('library','0.19.0','io.github.deniscerri.youtubedl-android'), ('common','0.19.0','io.github.deniscerri.youtubedl-android'), ('ffmpeg','0.19.0','io.github.deniscerri.youtubedl-android')]:
    aar = next((CACHE / group / artifact / version).rglob('*.aar'))
    jar = OUT / (artifact + '-api.jar')
    jar.write_bytes(zipfile.ZipFile(aar).read('classes.jar'))
    cp.append(jar)
glue = OUT / 'AppWiringCompileOnly.kt'
glue.write_text('''package com.swararia.audiolab
class AppContainer { val downloadQueue: DownloadQueueRepository get() = error("compile only") }
class SwarariaApp : android.app.Application() {
    val container: AppContainer get() = error("compile only")
    val library: MediaLibrary get() = error("compile only")
}
class MainActivity : android.app.Activity()
object R { object drawable { const val ic_audio: Int = 1 } }
''')
subprocess.run([JAVA, '-cp', ':'.join(map(str, launch)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, cp)), '-d', str(OUT / 'service.jar'), str(source), str(sources / 'IngestService.kt'), str(glue)] + list(map(str, contracts)), check=True)
print('PASS: service/native API Kotlin compilation (app wiring declarations only; no device execution)')
subprocess.run([JAVA, '-cp', ':'.join(map(str, [OUT / 'tests.jar'] + cp)), 'org.junit.runner.JUnitCore', 'com.swararia.audiolab.BatchDownloadRunnerTest', 'com.swararia.audiolab.DownloadPublicationTest'], check=True)
