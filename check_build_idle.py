"""Read-only guard: classify argv, not substrings in our own shell command."""
from pathlib import Path
ROOT=str(Path(__file__).resolve().parent).encode()
active=[]
for process in Path('/proc').iterdir():
    if not process.name.isdigit():continue
    try:argv=(process/'cmdline').read_bytes().split(b'\0')
    except (FileNotFoundError,PermissionError,ProcessLookupError):continue
    if argv and argv[0].endswith(b'/java') and ROOT in argv[0] and b'org.gradle.launcher.daemon.bootstrap.GradleDaemon' in argv:
        active.append(process.name)
assert not active, 'A Gradle daemon for this project is still active: '+str(active)
print('No concurrent project Gradle daemon.')
