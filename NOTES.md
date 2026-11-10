# Android debug artifact handoff — phone QA pending

## Scope
Approved isolated Android 10+ prototype: authorized URL -> native on-phone ingest -> local playback -> rename. Build tooling: local JDK 21, Gradle 8.13, Android SDK 36/Build Tools 36.0.0, AGP 8.13.2, Kotlin 2.2.21, Compose, Media3, Room, and pinned downloader/FFmpeg 0.19.0. No root/global install, Android Studio, emulator, NDK, paid backend, Git push or public release.

Desktop reference was inspected at 8f7b435a736f84dbba22d276cd9e88e7eedea606; desktop repository is not modified. This is an adaptation, not a WebView wrapper and not a tested complete conversion.

## Recovery
Workspace was created only after confirming the target did not exist. Rollback is scoped removal of this new workspace after any valuable artifacts have been copied. No existing repository history was rewritten. Device data needs separate cleanup as explained in DEVICE-TEST.md.

## Evidence so far
- JDK/Gradle/SDK packages downloaded with official published checksum comparisons; toolchain-lock.json records provenance.
- SDK CLI 23 reports --licenses is no longer needed; no legacy license file was fabricated. User approved SDK licensing in the installation plan.
- First Gradle attempt unexpectedly selected AGP default Build Tools 35; fixed by explicitly pinning the approved 36.0.0 and disabling automatic SDK component downloads. Build Tools 35 was not installed.
- A real Gradle/JUnit failure was observed for missing naming rules, followed by a passing implementation.
- Real Kotlin/JUnit RED->GREEN checks cover unsafe filenames, preserving the audio extension, and URL validation. test_core.py runs the already resolved Kotlin compiler without a fresh Gradle daemon.
- The APK ingest packaging acceptance test was observed failing because the APK was not yet present.

## Limits
No Android device/emulator is available, so native engine execution, MediaStore permissions, actual playback/background controls, rename/undo, and rendered UI remain unverified until phone testing. Never replace these with fabricated outputs or a mockup labeled as an actual screenshot.

## Built result
All three screens and persistent rename history are now in the installable debug APK. The full Gradle build passed, with four Kotlin/JUnit tests, one real host CLI compatibility check and three APK-content checks passing. Both ABI APKs pass SDK signature and ZIP-alignment checks; use verification/verification.json for exact current hashes.

Independent audit found a real bundled CLI mismatch. It was reproduced and corrected using official yt-dlp 2026.10.26 as a verified raw-resource override. A --version probe was false-green because the parser exits early; the regression correctly uses --list-extractors.

The initial player preflight accidentally matched its own command text and never started Gradle. check_build_idle.py now classifies a real Java executable and exact Gradle main-class argument. The later full build actually ran and passed.

Five nested ARM64 FFmpeg WebP libraries fail 16KB alignment; this was confirmed on the actual APK. Ingest explicitly requires 4KB pages. First optional lint with a 256MB Gradle heap timed out without a report. Retrying with the existing 512MB project heap passed (lintDebug, exit 0); exact warning counts are in verification/lint-summary.json. This workspace is not a Git repository; no commit was made. A heuristic 21-file own-source security scan reported no selected patterns, not a security certification.

No phone/emulator test or UI render was performed. Native-source/public-release licensing is pending. The full standalone APK exceeds the standard Telegram Bot API 50MB limit, so transfer needs an approved method. No external file upload, extra server, port or new archival tool has been performed.
