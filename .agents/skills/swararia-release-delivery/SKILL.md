---
name: swararia-release-delivery
description: >-
  Use when building Debug or signed Release APKs for swararia-audio-player-android,
  delivering release APKs to the user's Windows Downloads folder on WSL2, verifying
  APK signatures/DEX contents, or providing USB/Wireless ADB install instructions.
---

# Swararia Release & ADB Delivery

## 1. Pre-Build Fast Gates
Run fast contract and standalone JVM tests before invoking Gradle:
```bash
python3 test_ui_source_contracts.py && python3 test_ui_integration.py && python3 test_upgrade_03.py && python3 test_architecture.py && python3 test_search.py && python3 test_ui_rules.py && python3 test_architecture_wiring.py
```

## 2. Build & Verify APKs
Verify signing configuration exists without exposing secrets (`test -f keystore.properties`), then run sequential Gradle tasks:
```bash
bash build.sh testDebugUnitTest compileDebugKotlin lintDebug assembleDebug assembleRelease
python3 test_apk_contract.py
```
Verify release APK signatures:
```bash
.toolchain/android-sdk/build-tools/*/apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
.toolchain/android-sdk/build-tools/*/apksigner verify --print-certs app/build/outputs/apk/release/app-universal-release.apk
```

## 3. Deliver Release APKs to Windows Downloads
Copy verified split & universal APKs with matching SHA-256 check to `/mnt/c/Users/tsimp/Downloads/`:
```bash
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk /mnt/c/Users/tsimp/Downloads/swararia-0.6.0-spike-arm64-v8a-release.apk
cp app/build/outputs/apk/release/app-universal-release.apk /mnt/c/Users/tsimp/Downloads/swararia-0.6.0-spike-universal-release.apk
sha256sum app/build/outputs/apk/release/app-arm64-v8a-release.apk /mnt/c/Users/tsimp/Downloads/swararia-0.6.0-spike-arm64-v8a-release.apk app/build/outputs/apk/release/app-universal-release.apk /mnt/c/Users/tsimp/Downloads/swararia-0.6.0-spike-universal-release.apk
```
*(Adjust version string dynamically from `app/build/outputs/apk/release/output-metadata.json` if `versionName` changes.)*

## 4. Provide User Install Commands (Do Not Execute `adb install`)
- Ensure WSL does not hold port `5037`: `adb kill-server 2>/dev/null || true`.
- **Windows USB Debugging (PowerShell):**
  ```powershell
  adb devices
  adb install -r C:\Users\tsimp\Downloads\swararia-<versionName>-arm64-v8a-release.apk
  ```
- Remind the user about `adb uninstall com.swararia.audiolab.spike` once when switching between Debug and Release certificates.
