# Swararia Android Workspace Rules

## Device & Install Ownership
- Do NOT run `adb install` on the user's phone unless explicitly asked. Build and verify locally, then provide the exact copy-paste `adb` command for the user to run.
- When the user switches between Debug and Release builds (`com.swararia.audiolab`), note that differing signing keys require a one-time `adb uninstall com.swararia.audiolab` (resets internal app playlists/DB; `/Music` audio files on storage stay safe).

## WSL2 & Windows Interop
- Never leave a Linux `adb` daemon running inside WSL when the user is connecting via USB debugging on Windows—both contend on `tcp:5037` (`protocol fault` / empty `adb devices`). Ensure WSL `adb kill-server` is run if `adb` was invoked in WSL.
- Wrap every Windows interop command run from WSL (`cmd.exe`, `adb.exe`, `where.exe`) in `timeout <N>s ... < /dev/null`. Avoid slow PowerShell CIM/WMI cmdlets like `Get-PnpDevice`.

## Module & Test Gate Boundaries
- Keep `YoutubeSearchData.kt`, `ThumbnailPolicy.kt`, and `OnlinePreviewData.kt` free of Android APIs and `kotlinx.coroutines` (`test_search.py` compiles them with stdlib + Jackson + JUnit only).
- Keep `SearchContracts.kt`, `SearchSession.kt`, `PreviewSession.kt`, `PreviewResolver.kt`, and `PreviewUrlCache.kt` free of Android framework dependencies (`test_architecture.py`).
- Run fast Python contract & standalone JVM suites (`test_ui_source_contracts.py`, `test_ui_integration.py`, `test_upgrade_03.py`, `test_architecture.py`, `test_search.py`, `test_ui_rules.py`, `test_architecture_wiring.py`) before full Gradle builds, and check `test_apk_contract.py` after building debug APKs.
