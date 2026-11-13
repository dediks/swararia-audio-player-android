# Android native Remote review fixes — host RED/GREEN evidence

Base: `34fb6f7aac391ff8c7d83f9ee139d107337aaa6f`; candidate branch `fix/native-remote-review-0.5.1` (uncommitted).

## Persistence ordering / lifetime

RED: adapted the existing cached compiler review harness to the live source and ran the actual `DesktopRemoteSession` with a controlled non-FIFO IO dispatcher and fake store/API. Existing inventory: 15 passed. Both review regressions failed:

- `disconnectThenOwnerShutdownMustNotRestoreForgottenCredential`: expected null; persisted credential survived disconnect + release + caller-scope cancellation.
- `delayedOldClearMustNotEraseNewApprovedSession`: previous delayed clear erased the newly approved saved session.

GREEN: six promoted `DesktopRemotePersistenceTest` regressions pass through production session/coordinator: disconnect plus shutdown, reordered IO scheduler, forgotten restore across owners / retired late clear, queued save plus forget/cancellation, in-flight save with owner replacement, and reported storage exceptions / later recovery. The promoted reordered-IO regression drains the ordered chain before asserting final state; the old external repro's intermediate expectation that a later save must finish ahead of the older clear is intentionally not retained. That intermediate timing is not an acceptance criterion for correctly ordered persistence.

Root cause: clears lived inside the cancelable ViewModel scope, disk operations were dispatched without shared intent order, and the original AppContainer created a new store/lock for every ViewModel. Production now injects a single application-owned persistence coordinator around a single unchanged Keystore adapter; per-session epochs alone were not used as the cross-owner fix.

## Catalog containment

RED: `catalogProtocolFailurePreservesVerifiedTransportAndCanRecover` failed with `expected active but was disconnected` after a valid extended catalog was followed by a catalog-only PROTOCOL failure.

GREEN: protocol and unavailable catalog failures retain verified basic transport/role and stored pairing, remove stale catalog IDs/actions, allow basic Play, and recover on the next refresh. Separate real-session tests pass for catalog AUTH/TRUST forget and propagated cancellation without fresh controls. Existing expiry, role/revocation, freshness, and no-replay tests remain green.

## Capability opt-in

RED: the real loopback HTTPS harness failed `Capabilities opt-in missing or sent outside capabilities GET` before transport changes (Kotlin compile succeeded; TLS rejection checks passed).

GREEN: the actual HTTPS fixture now records the exact `X-Swararia-Capabilities: extended-v1` on the capabilities GET and its absence from other routes. Full existing wire checks pass, including total deadline, socket cancellation, no ambiguous POST replay and authorization. No production endpoint policy was relaxed; existing test-only loopback reflection remains in the probe.

## Distinguishable current source

RED: `verification/test_current_source_version.py` failed because `versionCode 7` was absent (source still 6 / 0.5.0-spike).

GREEN: current source guard passes with 7 / 0.5.1-spike and unchanged applicationId. Updated current APK expectations and packaging allowlist; historical outputs were not rewritten and no new APK result is claimed.

## Stable-candidate commands / actual results

- `python verification/run_desktop_remote_session_checks.py`: compiler exit 0; 24 JUnit tests pass (single inventory, no double-counting).
- `python verification/test_desktop_remote_wiring.py`: 3 pass.
- `python verification/test_current_source_version.py`: 1 pass.
- `python verification/run_data_checks.py`: data Kotlin compile; 13 JUnit + 8 source/SQLite pass.
- `python verification/run_desktop_remote_checks.py`: actual loopback HTTPS pass, including header-only-on-route proof.
- `git diff --check`: pass.

Known compiler warning: existing Java URL(String) deprecation in DesktopRemoteHttpsApi. No new dependency/SDK/tool install, Gradle, APK build, stage/commit, push/merge, ADB/install, or desktop edits. AppContainer/AppViewModel wiring is source-contract checked, not full Android compilation. Keystore adapter behavior is unchanged; no device Keystore or process-death durability claim. Reported I/O exceptions do not poison the coordinator chain, but filesystem deletion failures and adapter-suppressed failures cannot be guaranteed successful.
