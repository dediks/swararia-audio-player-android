# Swararia 0.3.0 — approved overnight development

## Authorization and boundaries
User requested a prioritized plan followed by immediate execution without further questions. Work on `feature/search-player-0.3`, based on `adf7c655325ba5308c43f00b455bde4556b0e8bf`. No new packages, SDKs, accounts, public hosting, GitHub push or release publication. Reuse the existing local toolchain/cache/signing identity. ADB work is paused. Preserve native ingest, storage, database schema/app ID and previous APKs.

Recovery: `.checkpoints/before-0.3.0-source.zip` (105 members; ZIP integrity and SHA verified), details in `verification/baseline-0.3.0.json`. `.toolchain` and `.cache` are ignored symlinks to the earlier build workspace; no tool installation occurred.

## Acceptance criteria
- YouTube Music default provider.
- Songs/Playlists search filters; playlists open individual tracks, not automatic bulk download.
- Sort by relevance, title A–Z and duration ascending/descending.
- Prioritize verified channels/artist-source indicators, with honest labeling rather than unproven ownership claims.
- Search results show actual channel names and song durations; do not substitute provider/artist names for an unknown uploader. Enrich missing values and identify incomplete metadata honestly.
- Online audio preview up to 30 seconds, explicit stop, no library insertion, no simultaneous main/preview playback, cleanup on screen/background changes.
- Load more results in bounded pages; retain previous results, avoid duplicates/stale queries.
- Playback: loop current track, continue queue normally, or pause after one track.
- Fade 3s: fade current app volume to zero over 3000ms, pause, restore original volume for later playback; handle cancellation/lifecycle safely.
- Build/unit/lint/signature/archive checks and one independent final review. Device behavior remains unverified until user testing.

## Implementation notes so far
The pinned upstream yt-dlp Music search extractor drops duration/artist/thumbnail fields already present in its renderer. An app-owned extractor plugin retains those fields without extra requests or modifying the pinned engine ZIP. Plugin loading must target the directory CONTAINING plugin packages (host: `app/src/main/assets` contains `search_plugins/yt_dlp_plugins/...`), not the `yt_dlp_plugins` parent directly.

Real host metadata-only probes confirmed Music song durations and artist names via the plugin, and both provider playlist searches returned canonical playlist IDs, titles and creator channels. Music song artist is deliberately not relabeled as upload channel. A real YouTube oEmbed probe returned actual `author_name`, so bounded/cached enrichment will supply missing channel names.

Playback implementation is delegated with exclusive ownership of PlaybackService.kt and new PlaybackControls.kt/PlaybackRules.kt/tests. Parent owns search, preview, Activity and UI integration. No parallel Gradle builds.

## Verification / remaining
Implemented: Music default; song/playlist filters and track selection; relevance/title/duration sort of loaded results; verified/artist-source indicators; mandatory genuine channel and song duration with bounded enrichment; 10-item pages up to 50, dedup/generation guards; online clipped preview and lifecycle/main-player cleanup; three native playback modes and fade-to-pause/volume restoration. Retry keeps the active playlist/page; playback-control polling is foreground-scoped.

Version `0.3.0-spike`, versionCode 3. Actual offline Gradle compilation, 23 unit tests (zero failure/error/skipped), lint (15 warnings, zero Error/Fatal), and both ABI APK builds succeeded. Four APK packaging contracts and six upgrade wiring contracts passed. Six live host metadata-only cases (both providers/kinds, Music page 2 and opening a playlist) each returned 10 complete records. These are host/source tests, not device UI/audio evidence.

APK signatures/zip alignment verified; old/new certificates, package ID, schema/core hashes and native/engine payload bytes compared. Previous APK hashes remain unchanged. See `verification/checks-0.3.0.json`, `verification/update-compatibility-0.3.0.json` and `verification/search-e2e-live-0.3.0.json`.

Preview extraction on this server received YouTube's sign-in/anti-bot error. The app handles failure without login/cookies/bypasses; successful phone preview, audio boundaries, fade timing/interruption, UI/font scaling and in-place installation remain unverified. Use `DEVICE-TEST.md` for the phone matrix. Independent final re-review passed (`verification/search-player-review-0.3.0.json`). Packaging uses `python package_update.py 0.3.0-spike` and `python package_telegram.py 0.3.0-spike`; resulting artifact/source/transfer hashes are recorded in the versioned `dist` manifests.

A first independent review found three issues: lifecycle cleanup and failed-request retry were corrected by the parent; a separate fix agent corrected playlist watch/VL endpoint metadata and textual creators. Eight bundled-engine plugin fixture tests now pass, including missing creator and song/artist separation cases. Final independent re-review passed with no concrete security/logic findings; behavioral/device-test suggestions remain recorded.

Recovery: keep the old APK and source checkpoint. Restore baseline source into a separate clean worktree, preserving this branch; do not blindly reset current work. A normal installer may reject versionCode 2 after installing 3. Do not uninstall to claim data-preserving rollback; an authorized recovery build using the same signer and an acceptable versionCode would require its own build/verification.

Existing limitations (including nested ARM64 FFmpeg 16KB alignment and unresolved native public-distribution provenance) remain; they were not fixed in this update. No new packages installed, no GitHub push/public release, and no ADB changes.
