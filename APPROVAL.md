# Scope and approval

User approved the disclosed local toolchain installation, Android SDK licensing, and an isolated Android 10+ proof of concept.

Acceptance path: authorized URL -> ingest on the Android device -> local playback -> rename without changing the audio format.

Deliverables: debug APK and source with verification notes. No public release, GitHub push, production deployment, root install, Android Studio, emulator or NDK is authorized. Desktop repository is unchanged.

Build tooling and caches are confined to this project. Device-runtime and UI validation require a physical phone because no emulator is available here. No fabricated download, playback or device-test results are permitted.

Native downloader candidate: deniscerri youtubedl-android 0.19.0 (GPLv3), FFmpeg module included. The source API was read before implementation. It includes a QuickJS runtime; no extra Deno installation is planned.
