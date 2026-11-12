# Contribution and verification

## Scope and architecture
Read ARCHITECTURE.md before changing app code. Keep repository/platform construction in AppContainer, screen orchestration in the ViewModel/SearchSession, native/network/file operations in their adapters, and drawing/local UI state in feature components. Share stateless controls only when they have real consumers. Existing MediaController presentation helpers remain platform adapters, not repository operations.

Do not add dependencies, change SDKs, migrate/reset data, replace signing identity, push or publish as an incidental refactor. UI concept approval is a separate gate from implementation. Preserve user work and excluded design artifacts.

## Fast feedback
Use existing cached tooling, not another installed test framework:

```sh
python test_architecture.py
python test_architecture_wiring.py
python test_ui_rules.py
python test_search.py
```

The first command exercises Android-free orchestration with an injected repository and controlled pending/cancelled work. Structural contracts are not behavioral tests. Live network tests are opt-in (`python test_search.py --live`), may be externally blocked, and never prove phone playback.

## Integration gate
Run Gradle jobs sequentially on this constrained host:

```sh
bash build.sh testDebugUnitTest --offline
bash build.sh lintDebug --offline
bash build.sh assembleDebug --offline
python test_ui_source_contracts.py
python test_ui_integration.py
python test_upgrade_03.py
python test_apk_contract.py
python verify_artifacts.py
python verify_architecture.py --apk
```

`verify_architecture.py` requires this refactor's local recovery snapshot and previous candidate APKs; those private/excluded artifacts are intentionally absent from a fresh public checkout. It is a transition-specific preservation audit, not a replacement for portable unit tests. Set up any missing approved toolchain/dependencies explicitly; do not remove offline guards and silently install SDK packages to make a check pass.

Review exact diffs, record real results and remaining device limitations, and obtain independent review before committing. Commit only scoped source/evidence; never toolchains, caches, signing keys, APKs or unrelated mockups. A verified local commit is not permission to push.

## Device and safety gates
Use DEVICE-TEST.md when a phone is available. Tests/builds/structural equality do not establish font scaling, audio timing or lifecycle behavior on Android. For deletion/queue/artwork/schema changes, add explicit behavioral and storage/migration tests before implementation. Never promise filesystem+Room atomicity, recovery after process death, or commercial native licensing clearance without evidence.
