"""Install the approved isolated Android build toolchain. No root/global changes."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / '.toolchain'
DOWNLOADS = TOOLS / 'downloads'
SDK = TOOLS / 'android-sdk'
PACKAGES = [
    {'name':'jdk21', 'url':'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz', 'hash':'d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c', 'algorithm':'sha256', 'file':'jdk21.tar.gz', 'dest':str(TOOLS/'jdk')},
    {'name':'gradle8.13', 'url':'https://services.gradle.org/distributions/gradle-8.13-bin.zip', 'hash':'20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78', 'algorithm':'sha256', 'file':'gradle8.13.zip', 'dest':str(TOOLS/'gradle')},
    {'name':'android-commandline23', 'url':'https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip', 'hash':'e025545c62a8e64c7559119566a569fb1dec5f60', 'algorithm':'sha1', 'file':'cmdline-tools23.zip', 'dest':str(SDK/'cmdline-tools'/'latest')},
    {'name':'android-platform36-r02', 'url':'https://dl.google.com/android/repository/platform-36_r02.zip', 'hash':'2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576', 'algorithm':'sha1', 'file':'platform36.zip', 'dest':str(SDK/'platforms'/'android-36')},
    {'name':'android-buildtools36.0.0', 'url':'https://dl.google.com/android/repository/build-tools_r36_linux.zip', 'hash':'b0b6376977657e8ad9b969bacf4093601da2c6fb', 'algorithm':'sha1', 'file':'build-tools36.zip', 'dest':str(SDK/'build-tools'/'36.0.0')},
]

def checksum(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def obtain(package):
    path = DOWNLOADS/package['file']
    if path.exists() and checksum(path, package['algorithm']) == package['hash']:
        print('Verified cached', package['name'], flush=True)
        return package, path
    partial = path.with_suffix(path.suffix + '.part')
    print('Downloading', package['name'], flush=True)
    request = urllib.request.Request(package['url'], headers={'User-Agent':'Hermes-approved-Android-prototype'})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open('wb') as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
    assert checksum(partial, package['algorithm']) == package['hash'], 'Checksum mismatch: '+package['name']
    partial.replace(path)
    print('Downloaded and checksum verified', package['name'], path.stat().st_size, flush=True)
    return package, path

def unpack(package, archive):
    destination = Path(package['dest'])
    if destination.exists():
        print('Already unpacked', destination, flush=True)
        return
    stage = TOOLS/('_unpack-'+package['name'])
    stage.mkdir(exist_ok=False)
    if archive.name.endswith('.tar.gz'):
        with tarfile.open(archive) as handle:
            handle.extractall(stage, filter='data')
    else:
        with zipfile.ZipFile(archive) as handle:
            for member in handle.infolist():
                resolved = (stage/member.filename).resolve()
                assert resolved.is_relative_to(stage.resolve()), 'Unsafe archive path'
            handle.extractall(stage)
            for member in handle.infolist():
                target = stage/member.filename
                mode = (member.external_attr >> 16) & 0o777
                if mode and target.exists():
                    target.chmod(mode)
    entries = list(stage.iterdir())
    assert len(entries) == 1 and entries[0].is_dir(), 'Unexpected archive layout: '+str(entries)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(entries[0]), str(destination))
    stage.rmdir()
    print('Installed locally', destination, flush=True)

if __name__ == '__main__':
    for folder in ['android', 'gradle', 'tmp']:
        (ROOT/'.cache'/folder).mkdir(parents=True, exist_ok=True)
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        packages = list(executor.map(obtain, PACKAGES))
    for package, path in packages:
        unpack(package, path)
    env = os.environ.copy()
    env.update(JAVA_HOME=str(TOOLS/'jdk'), ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK), ANDROID_USER_HOME=str(ROOT/'.cache'/'android'), GRADLE_USER_HOME=str(ROOT/'.cache'/'gradle'), TMPDIR=str(ROOT/'.cache'/'tmp'))
    env['PATH'] = str(TOOLS/'jdk'/'bin')+':'+env.get('PATH','')
    env['JAVA_TOOL_OPTIONS'] = '-Djava.io.tmpdir='+str(ROOT/'.cache'/'tmp')
    for executable in [TOOLS/'gradle'/'bin'/'gradle', SDK/'cmdline-tools'/'latest'/'bin'/'sdkmanager']:
        executable.chmod(0o755)
    sdkmanager = SDK/'cmdline-tools'/'latest'/'bin'/'sdkmanager'
    accepted = subprocess.run([str(sdkmanager), '--sdk_root='+str(SDK), '--licenses'], input='y\n'*100, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    (ROOT/'sdk-license-install.log').write_text(accepted.stdout)
    print('SDK license acceptance exit', accepted.returncode, flush=True)
    if accepted.returncode:
        print(accepted.stdout[-5000:], flush=True)
        raise SystemExit(accepted.returncode)
    for command in [[str(TOOLS/'jdk'/'bin'/'java'), '-version'], [str(TOOLS/'gradle'/'bin'/'gradle'), '--version']]:
        result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
        print(result.stdout, flush=True)
        assert result.returncode == 0
    (ROOT/'toolchain-lock.json').write_text(json.dumps({'packages':PACKAGES,'scope':'Local to prototype; no root, global PATH changes, Android Studio, emulator or NDK','sdk_licenses':'User approved SDK terms in the installation plan; CLI 23 reported --licenses is no longer needed. See sdk-license-install.log; no legacy license files were fabricated.'},indent=2)+'\n')
    print('TOOLCHAIN_READY', flush=True)
