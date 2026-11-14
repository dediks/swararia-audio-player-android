# Android 0.6.0 integrated source handoff

## Integrated work
Branch: `integration/android-0.6-remote-fixes`.
Parents: user `51fb79f3cc7cf9a0e5341cf097771a9874a24d1b` plus reliability fix `d19a86b7885c4e5f6761bfbf7ab6d26c7b3682a4`.
Source metadata: versionCode8 / versionName0.6.0-spike. Application ID, signing configuration, Roomv4 schema and migration history remain intact.

All four conflicts were resolved by retaining both implementations. The composition root provides the application-owned server/controller and exactly one shared persistence coordinator. The ViewModel receives both, retains server/deletion actions and releases the client without forgetting saved pairing when its owner clears. Automatic client fixes and combined wiring tests were retained. This was not a whole-file ours/theirs replacement.

Parent checks verified24 user feature files byte-identical to51,8 client/fix/test/fixture files byte-identical tod19, and19 design files unchanged. The actual new server/controller/service/browser/media/schema code is preserved, not rewritten or comprehensively audited by this integration task.

## Current acceptance record
The final source/host verdict and immutable candidate identity are in `verification/android-0.6-integration-final.json`. Final commit and archive/patch hashes are in the adjacent release manifest. `INTEGRATION-0.6.0.md` records the earlier pre-commit writer checkpoint; older0.5/0.5.1 reports remain historical rather than receipts for this merged source.

Fresh parent checks passed:24 client/session JVM tests; server engine4, TLS2, real HTTP/TLS loopback2; data13 JVM plus9 SQLite/schema contracts;3 combined integration source contracts;4 wiring checks;1 source-version check; browserJS syntax; and real Kotlin-to-desktop HTTPS transport/trust/authorization/timeout/cancellation/no-replay checks. The immutable51 server baseline also passed8 host tests using the same pinned cached toolchain.

The AppContainer/ViewModel integration is source-contract reviewed, not a full Android compilation. No Gradle/KSP/full lint/new APK, phone installation, real Keystore/MediaStore/scanner/font/audio/LAN acceptance is claimed. No dependency or SDK was downloaded. The existing URL(String) deprecation warning remains.

## Publication by the user
The merge commit is local on the integration branch, not merged into GitHub main. Publish only this Android integration branch, then create a PR with base `main` and compare `integration/android-0.6-remote-fixes`:

```sh
git push -u origin integration/android-0.6-remote-fixes
```

The integration includes the reliability-fix commit in its history; do not separately reapply its patch or merge the old0.5 feature branch to obtain it. Desktop source remains unchanged at8ec2df7 and is a separate publication decision.

The supplied patch is relative to exact user51fb79f base and includes the combined source delta; apply only to a clean matching base, never an already updated checkout. A patch reproduces source files, not the merge-parent history. Use the integration branch/commit when preserving Git history. ZIP is the exact committed Git tree. No credentials, signing keys, local caches or APK binaries are included.

## Recovery
The prior fix branch and both immutable parent commits are preserved. The private source bundle was verified through an isolated clone containing both parents; design ZIP bytes and CRC were checked before integration. Preserve newer work before a scoped rollback. Do not reset unrelated work, clear app data or uninstall. Reverting a merge requires selecting its parent deliberately after inspecting the requested recovery target; returning to a preserved branch does not update an installed APK or reverse a device database migration.
