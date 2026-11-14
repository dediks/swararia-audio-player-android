# Android 0.6.0 integration candidate (host evidence only)

## Scope and identity
- Pending local merge: `51fb79f3cc7cf9a0e5341cf097771a9874a24d1b` + `d19a86b7885c4e5f6761bfbf7ab6d26c7b3682a4`, common base `34fb6f7`.
- Source version: `8` / `0.6.0-spike` (`max(6, 7) + 1`); application ID, signing configuration and Room v4 unchanged.
- No commit/push, Gradle, downloads, APK build, install, ADB or device acceptance performed. Historical 0.5/0.5.1 evidence remains historical.

## Resolution
- AppContainer keeps Room migrations 1→2→3→4 and the application-owned remote server; additionally retains exactly one lazy DesktopRemotePersistence wrapping the unchanged Android Keystore adapter.
- AppViewModel receives both persistence and server. Session receives `persistence`, releases (does not forget) on clear; all server callbacks, deletion notifications/checker and service ownership are retained.
- README/architecture retain both implementations and distinguish source candidate from historical build evidence. Active APK contract, source-version guard and Telegram packaging version allowlist align to 0.6.0; packaging was not executed.
- Automatic merge of client protocol/session fixes, persistence tests and combined wiring contracts was retained, not replaced with whole-file ours/theirs.

## Regression-first evidence
`INTEGRATION_SOURCE_REV=51fb79f python verification/test_android_integration.py -v` fails all three named source contracts: shared persistence composition, combined VM lifecycle, and version/test inventory. The first attempted test used an incorrect `.kts` path (harness error); corrected to actual Groovy `app/build.gradle` and reran to obtain three assertion failures before production conflict resolution. Conflict markers are not the RED evidence.

Minimal production resolution is shared lazy persistence plus preserving server injection, changing VM persistence type/argument, and version metadata. These are host source contracts, not Android compilation/lifecycle execution.

## Candidate commands actually run
- `python verification/test_android_integration.py -v`: 3/3 PASS.
- `python verification/test_current_source_version.py`: 1/1 PASS.
- `python verification/test_desktop_remote_wiring.py`: 4/4 PASS (client persistence and server boundaries both present).
- `python verification/run_desktop_remote_session_checks.py`: compiler exit 0; real protocol/TLS/session/fade/persistence JVM inventory 24/24 PASS.
- `python verification/run_android_remote_server_checks.py`: compiler exit 0; server engine 4/4, TLS identity 2/2, real HTTP/TLS loopback 2/2 PASS. Initial helper compile omitted existing pure LibraryRules/UiPresentation; fixed helper source inventory, not production code. Listener binds and client sockets are explicitly 127.0.0.1; private-IP certificate validation is not a private-IP bind.
- `SWARARIA_CACHED_TOOLCHAIN_ROOT=/home/ubuntu/projects/swararia-audio-player-android python verification/run_data_checks.py`: real data Kotlin compilation exit 0, data/library/thumbnail JUnit 13/13 PASS, SQLite/schema contracts 9/9 PASS. Not Room/KSP generated migration or device execution.
- `node --check app/src/main/assets/remote_server/remote.js`: exit 0.
- Kotlin checks emit existing deprecated Java URL-constructor warning; no claim of warning-free compilation.

## Pending acceptance and recovery
Independent parent review/freeze and broader cross-wire validation remain separate. No claim for full Android compilation, lint, ZXing/scanner dependency availability, APK version/signature verification, Compose rendering, Keystore device behavior, LAN/Hotspot, MediaStore or physical playback. Process death and suppressed Keystore adapter I/O errors retain documented limitations.

Recovery inputs are the two immutable parent commits and parent-verified private bundle/design archive in the integration baseline metadata. Keep MERGE_HEAD pending for parent review; do not reset, stash, discard user data or abort this merge without scoped approval.
