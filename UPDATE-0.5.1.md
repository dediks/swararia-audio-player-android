# Android 0.5.1-spike — review-fix source candidate

Current source identity: `versionCode 7`, `versionName 0.5.1-spike`.
No 0.5.1 APK has been built, packaged, installed, or published by this change.
The existing 0.5.0 build/device reports remain historical and unchanged.

## Changes

- One application-owned `DesktopRemotePersistence` coordinates the real Keystore store across ViewModels. Disk intents are submitted in order before dispatch; already submitted forget/save tasks outlive caller cancellation. A replacement owner cannot load ahead of a submitted forget, and retired owners cannot submit late clears/saves against its credentials. Release preserves remembered sessions; explicit disconnect forgets them. No GlobalScope, worker loop, network retries, or main-thread disk I/O was added.
- A catalog-only protocol/unavailable error clears catalog IDs/groups and disables catalog selection, without deleting a valid pairing or discarding freshly verified basic transport/role. Refresh can recover the catalog. AUTH/TRUST still disconnect and forget; cancellation is not converted to catalog success/failure feedback.
- Only `GET /v1/capabilities` carries `X-Swararia-Capabilities: extended-v1`. Legacy three-command replies remain supported. Catalog responses remain bounded to `98_304` bytes; TLS pin/SAN checks, eight-second total deadline, no redirects/cookies, and no POST replay are retained.
- Current source/APK expectation guards now distinguish 0.5.1 from installed 0.5.0. No app ID, signer configuration, Room v3 schema/migrations, library/player, crypto/file format, or scanner dependency change.

## Host verification actually executed

- `python verification/run_desktop_remote_session_checks.py`: cached Kotlin compiler exit 0; **24 JUnit tests passed**, including six persistence race/failure regressions and three catalog boundary regressions.
- `python verification/test_desktop_remote_wiring.py`: **3 passed**, including application-singleton persistence injection.
- `python verification/test_current_source_version.py`: **1 passed** (source identity only).
- `python verification/run_data_checks.py`: direct data-source compilation passed; **13 JUnit + 8 source/SQLite tests passed**.
- `python verification/run_desktop_remote_checks.py`: real Kotlin transport to loopback Desktop HTTPS passed TLS rejection, approval/capabilities/catalog/state, command rejection without physical audio, ambiguous POST/no replay, slow-drip deadline, cancellation, exact route-specific capability header, view-only and revocation checks.
- `git diff --check`: passed.

TDD RED/GREEN details: `verification/android-remote-review-fixes-0.5.1.md`.

## Limits / pending acceptance

These are cached-toolchain host checks, not Gradle/KSP/lint, Android lifecycle/Keystore execution, scanner, APK, device UI/audio, or Windows/LAN acceptance. Gradle was deliberately not invoked: the existing Google Code Scanner `16.1.0` dependency is uncached on this host; no dependencies/SDK were downloaded. Current APK contracts were updated but not run against the old APK.

Independent finite disk jobs protect against coroutine/ViewModel cancellation, **not process death, filesystem failure, or failed deletion**. Exceptions reported by a store do not poison the operation chain and a reported failed save can be retried on a later read. The unchanged Keystore adapter remains best-effort and internally suppresses some I/O failures; no durable-write/deletion acknowledgement or crash-atomic intent journal is claimed. A process killed before the forget reaches disk can retain the old encrypted record. Device testing and approved build/dependency availability remain separate gates.

No stage, commit, push, merge, installation, public APK distribution, data reset, or desktop source change was performed.
