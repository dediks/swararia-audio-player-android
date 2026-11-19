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
Detect the Windows Downloads folder dynamically (`WIN_USER=$(timeout 5s cmd.exe /c "echo %USERNAME%" < /dev/null 2>/dev/null | tr -d '\r')`) and copy verified split & universal APKs with a `SHA256SUMS.txt` manifest:
```bash
VERSION=$(python3 -c "import json; print(json.load(open('app/build/outputs/apk/release/output-metadata.json'))['elements'][0]['versionName'])")
WIN_DOWNLOADS="/mnt/c/Users/${WIN_USER}/Downloads"
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk "${WIN_DOWNLOADS}/SWR-Player-${VERSION}-arm64-v8a-release.apk"
cp app/build/outputs/apk/release/app-universal-release.apk "${WIN_DOWNLOADS}/SWR-Player-${VERSION}-universal-release.apk"
(cd "${WIN_DOWNLOADS}" && sha256sum "SWR-Player-${VERSION}-arm64-v8a-release.apk" "SWR-Player-${VERSION}-universal-release.apk" > "SWR-Player-${VERSION}-SHA256SUMS.txt")
sha256sum app/build/outputs/apk/release/app-arm64-v8a-release.apk "${WIN_DOWNLOADS}/SWR-Player-${VERSION}-arm64-v8a-release.apk" app/build/outputs/apk/release/app-universal-release.apk "${WIN_DOWNLOADS}/SWR-Player-${VERSION}-universal-release.apk"
```

## 4. Provide User Install Commands (Do Not Execute `adb install`)
- Ensure WSL does not hold port `5037`: `adb kill-server 2>/dev/null || true`.
- **Windows USB Debugging (PowerShell):**
  ```powershell
  adb devices
  adb install -r $env:USERPROFILE\Downloads\SWR-Player-<versionName>-arm64-v8a-release.apk
  ```
- Remind the user about `adb uninstall com.swararia.audiolab` once when switching between Debug and Release certificates.
