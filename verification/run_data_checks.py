"""Host-only data checks using the already-cached toolchain; never invokes Gradle.
Override SWARARIA_CACHED_TOOLCHAIN_ROOT when cached tooling lives elsewhere.
No generated Room DAO/device migration evidence is produced by this check.
"""
from pathlib import Path
import os
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = Path(os.environ.get('SWARARIA_CACHED_TOOLCHAIN_ROOT', '/home/ubuntu/projects/audio-library-android-spike'))
CACHE = BASE / '.cache/gradle/caches/modules-2/files-2.1'
SCRATCH = Path(os.environ.get('TMPDIR', str(Path.home() / '.hermes/cache/scratch'))) / 'swararia-data-checks'
SCRATCH.mkdir(parents=True, exist_ok=True)
JAVA = str(BASE / '.toolchain/jdk/bin/java')
LIB = BASE / '.toolchain/gradle/lib'

def run(args):
    subprocess.run(args, cwd=ROOT, check=True)

cp = [BASE / '.toolchain/android-sdk/platforms/android-36/android.jar']
for group, artifact, version in [('androidx.room','room-runtime-android','2.8.4'), ('androidx.room','room-common-jvm','2.8.4'), ('androidx.sqlite','sqlite-android','2.6.2'), ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'), ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'), ('org.jetbrains','annotations','23.0.0')]:
    for path in (CACHE / group / artifact / version).rglob('*'):
        if path.suffix == '.jar':
            cp.append(path)
        elif path.suffix == '.aar':
            output = SCRATCH / (artifact + '.jar')
            output.write_bytes(zipfile.ZipFile(path).read('classes.jar'))
            cp.append(output)
compiler = next((CACHE / 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').rglob('*.jar'))
launch = [compiler] + list(LIB.glob('trove4j-*.jar')) + list(LIB.glob('annotations-*.jar')) + list(LIB.glob('kotlin-reflect-*.jar')) + [p for p in cp if 'kotlin-stdlib' in str(p) or 'coroutines' in str(p)]
command = [JAVA, '-cp', ':'.join(map(str, launch)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath']
source = ROOT / 'app/src/main/java/com/swararia/audiolab'
names = ['AudioDatabase','AudioLibraryRepository','MediaLibrary','AudioMigrations','DownloadQueueRepository','DownloadQueueContracts','DownloadQueuePublication','DownloadJob','DownloadRequest','DownloadQueueRules','ArtworkRules','ArtworkStore','LibraryRules','ThumbnailPolicy','ThumbnailTransfer']
run(command + [':'.join(map(str, cp)), '-d', str(SCRATCH / 'data.jar')] + [str(source / (name + '.kt')) for name in names])
print('PASS: direct Kotlin compilation of data sources (no Gradle/KSP)', flush=True)
pure = ['DownloadQueueRules','ArtworkRules','LibraryRules','ThumbnailPolicy','ThumbnailTransfer']
tests = ['DataRulesTest','LibraryRulesTest','ThumbnailPolicyTest']
run(command + [':'.join(map(str, cp + [LIB / 'junit-4.13.2.jar'])), '-d', str(SCRATCH / 'rules.jar')] + [str(source / (name + '.kt')) for name in pure] + [str(ROOT / 'app/src/test/java/com/swararia/audiolab' / (name + '.kt')) for name in tests])
run([JAVA, '-cp', str(SCRATCH / 'rules.jar') + ':' + str(LIB / '*'), 'org.junit.runner.JUnitCore'] + ['com.swararia.audiolab.' + name for name in tests])
run(['python', str(ROOT / 'verification/test_data_layer.py')])
