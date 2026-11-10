# Third-party notices

The downloader wrapper/FFmpeg modules are pinned to `io.github.deniscerri.youtubedl-android:0.19.0`; upstream POM declares GPLv3. The actual FFmpeg build uses `--enable-gpl --enable-version3`. Official module source JARs, the GPL text and fetch hashes are in `third-party/`.

Bundled native Python is 3.12.11 (PSF); QuickJS is 2025-04-26 (MIT). Native binaries are upstream prebuilds, not rebuilt here. The corrected yt-dlp resource is official 2026.10.26, verified against official SHA-256 sums; see `engine-lock.json`. EJS scripts are included in that resource.

**Public-distribution compliance is not cleared.** Exact corresponding native source, patches and build provenance have not been verified. Module source JARs are not claimed to fulfill all native-source obligations. Complete that audit and decide the application license before a public/commercial release. No desktop-repository license was changed.

Five WebP-related ARM64 libraries inside the FFmpeg ZIP have 4KB alignment. The app explicitly blocks online ingest on non-4KB device pages. APK ZIP alignment passing does not establish nested ELF 16KB support. Device execution remains untested. Details are in `verification/verification.json`.
