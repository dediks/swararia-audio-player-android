# Android0.5.1 source handoff

Base:34fb6f7aac391ff8c7d83f9ee139d107337aaa6f.
Branch:fix/native-remote-review-0.5.1 (new fix branch from the already merged main).
Source version: versionCode7 / versionName0.5.1-spike. Application ID, signing path, Roomv3 schema and existing library data model unchanged. No APK was built or delivered. The final commit identifier and source/patch hashes are in the adjacent release manifest; independent verdict in verification/review-fixes-final.json.

## Changes
- One application-owned ordered persistence coordinator shared across ViewModels. Disk operation order is reserved before dispatch; submitted work is not cancelled by the ViewModel. Retired owners cannot submit late mutations against replacement credentials.
- Catalog-only protocol/unavailable errors clear stale catalog IDs/actions while keeping verified basic transport and pairing; Refresh can recover. AUTH/TRUST/expiry remain fail-closed and cancellation propagates.
- The actual HTTPS client requests X-Swararia-Capabilities: extended-v1 only for capabilities GET. New desktop defaults to the legacy three commands; old desktop can ignore the header. Existing trust/pinning, response/time bounds and no POST replay remain.
- Current source/version guards and packaging allowlist recognize0.5.1 without rewriting historical APK results.

## Evidence
Parent reran24 remote JUnit cases,13 data JUnit,8 SQLite/source-contract cases,3 wiring and1 source-version check; all pass. Initial persistence and catalog regressions were observed failing before implementation. Actual Kotlin-to-desktop HTTPS wire checks pass for new/legacy capability paths, Unicode catalog bounds, trust, permissions, no replay, deadlines and cancellation. AppContainer/ViewModel wiring is source-contract checked, not full Android compilation. Operation ordering surviving coroutine cancellation is not a promise of process-death durability or successful deletion under storage/adapter failure.

## Manual publication
No push performed. After reviewing the local commit:

```sh
git push -u origin fix/native-remote-review-0.5.1
```

Then create a GitHub PR with base main and compare fix/native-remote-review-0.5.1. Do not merge the old0.5 feature branch again to obtain these newer fixes. The supplied patch contains only changes from the exact base above; do not apply it to an already updated tree. Installing a future compatible APK is a separate step; do not uninstall/clear data to resolve signing/version conflicts.

## Verification boundary
This is a source-only release. No automatic push, merge to main, APK installation, Windows firewall/portproxy mutation, dependency download or SDK installation was performed. Tests use cached host tooling and explicit loopback fixtures. Physical Windows/UAC/WSL networking, real Android Keystore/process death, scanner/Play Services, device audio/layout and full Gradle/KSP/APK build remain unverified. The uncached scanner16.1.0 dependency prevents claiming a fresh Android APK. Existing URL(String) deprecation warning remains.

## Recovery
Source bundles and tested isolated restores were created before fixes; the existing22-file design preview was separately backed up and remains byte-identical. Preserve newer work and use a scoped revert of the fix commit if rollback is requested. Do not reset the whole repository, uninstall Android, clear data or delete unrelated design/library files. A source backup does not restore a running application or an installed phone's data.
