# Swararia 0.2.0 update

## Approved scope and order
Search implementation/review first, then native UI, then Activity/logo integration, then APK checks/handoff. No new packages, SDKs, tools, API key, server, database migration, app-ID/signer changes, repository push or public hosting. The user approved implementation and GPT-6.1 Sol/Medium; the profile effort was changed via the supported config CLI, with private backup and resolver readback. CLI schema warns about the key, but the installed gateway explicitly resolves it at each messaging turn; requests already in progress cannot change retroactively.

## Implemented
- Native Pustaka with real library search/sort, rename/history/undo; session mini-player, focused player and actual controller queue. Presentation formatting does not rename stored filenames/URIs.
- Tambah audio: Tautan and Cari (YouTube/YouTube Music), plus separate local import. Search is metadata-only; selecting a result opens fresh, unchecked rights confirmation, not an automatic download.
- Real loading/error/empty/results/cancel states; lifecycle-scoped playback listener/ticker; idle/ended resume does not rebuild the queue.
- MainActivity wires the new UI and reads fresh search/ingest busy state before dispatch, including after permission/picker callbacks.
- User logo is copied byte-identically from the approved transparent derivative for header use; launcher/splash derivatives retain its shapes/colors. Local Inter font and OFL license included. Abstract cover assets are decorative fallback artwork, not fabricated albums or waveforms. No mock tracks/design annotations ship in app code.
- Thumbnail requests use the existing strict HTTPS host policy, reject redirects, bound response bytes/bitmap dimensions/cache and use timeouts/error fallback. No new image library.
- versionCode 2, versionName `0.2.0-spike`, applicationId `com.swararia.audiolab.spike`, original signer and Room v1 schema retained.

## Verification completed on build host
- Existing backend red/green tests and live metadata probes returned 10 actual hits per provider on the VPS; these are not Android-native runtime tests. Missing metadata remains unknown.
- Pure UI/online-gate/thumbnail-transfer JVM checks: 3 tests passed; source contracts: 7 passed; integration/asset source contracts: 3 passed. New guard, asset, Activity and thumbnail-boundary tests were observed red before implementation/fix.
- Offline `compileDebugKotlin`, `testDebugUnitTest` and `assembleDebug` passed. Gradle suite: **14 tests, 0 failure/error/skipped**.
- Lint executed separately with existing 384m lint heap: **26 warnings, no Error/Fatal**. Warnings include dependency update advice, legacy launcher shape/themed icon recommendations, KTX suggestions and pre-existing manifest/resource advice; dependencies were not upgraded.
- Four APK contracts passed: native payload, services/storage permissions, organizer persistence classes, version/search/native UI/logo resources, and exclusion of design annotations.
- Both ABI APKs passed apksigner, ZIP integrity and ZIP alignment checks. `verification/update-compatibility-0.2.0.json` compares signer against the actual old APKs and verifies identical native/engine payload bytes, preserved core/schema source hashes and unchanged 0.1.0 artifacts. This does not claim a phone in-place install was exercised.
- Independent UI/integration review passed with no concrete security or logic defects (`verification/ui-update-review-result.json`, delegation `deleg_d1f827c8`). The reviewer explicitly requires physical-device checks for queue/stop/resume, permission/picker busy guards and small-screen rendering. Packaging requires the parsed passing verdict.

## Final packaging and handoff
All source/build/review gates passed. Commands: `python package_update.py` and `python package_telegram.py 0.2.0-spike`. They preserve old releases, package source including binary UI assets/licenses, verify release hashes, and rejoin/extract the two ZIP volumes to prove identical APK bytes. Transfer evidence is written to `dist/telegram-parts-0.2.0.json`. Deliver the two volumes directly in the current chat, plus the source ZIP and device test guide; no external host. Local packaging/attachment preparation is not confirmation of receipt or successful phone installation.

## Device verification and existing limits
No Android device/emulator is available in the approved build environment. UI rendering, font scaling/landscape, in-place install with existing data, native search cancellation/timeout and real provider/download/playback flows must still be exercised on the user's Samsung M15 Android 16. See `DEVICE-TEST.md`.

Native engine limitations are unchanged: five nested ARM64 FFmpeg WebP libraries remain 4KB-aligned; 16KB ingest compatibility is not claimed. ZIP alignment does not fix ELF alignment. Exact native corresponding-source/build provenance and general GPL distribution compliance remain unresolved; this is a private debug spike, not a cleared public production release.

## Recovery
Verified pre-update source archive: `.checkpoints/before-0.2.0-ui-search-source.zip`; baseline: `verification/0.2.0-baseline.json`. Additional source snapshot before parent integration: `.checkpoints/before-0.2.0-ui-integration-source.zip` (50 members; integrity checked). Both 0.1.0 APKs remain untouched.

Do not uninstall to roll back if preserving the existing library/rename database matters. A normal Android installer may reject installing versionCode 1 over 2; a corrective build based on the earlier source with a higher versionCode is a separate recovery action, not a tested automatic downgrade. Source backups are on this host and are not off-host disaster recovery.
