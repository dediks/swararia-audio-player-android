# Swararia Audio Lab 0.4.0-spike

Private Android test candidate. No repository push or public APK publication is authorized.
Baseline: `f2d0b3d1ab6c8f250733a42606100721f1a9888d` (reviewed architecture refactor).

## Changes
- Compact Search with YT Music default, songs/Playlist filtering, sorting, real channel/duration metadata and bounded paging.
- Dedicated Playlist view; returning restores loaded parent results and sort without another network request.
- Loaded-song multiselect (long press), Select all loaded, explicit fresh download-rights consent.
- Downloads page backed by a durable Room queue. One native worker at a time; per-job stages/progress, Cancel batch, Retry failed and explicit Start download for queued work after reopening.
- Cancel preserves completed tracks. A noninterruptible save/postprocessing phase can finish the current track; pending items are canceled. There is no transfer pause/resume or automatic background restart.
- Durable download requests accept public YouTube/YT Music video links (including ordinary youtu.be shares) and store only canonical public video IDs/pages. Playlist links, direct streams, signed/auth-bearing and unsupported link forms are rejected before enqueue; unsafe thumbnail queries are not persisted.
- A durable publication journal bridges MediaStore and Room: intent precedes pending-file creation; complete-copy readiness precedes visibility; track insertion and queue completion share one Room transaction. Recovery reconciles ready/visible copies instead of blindly downloading again. Uncertain storage state retains the journal and blocks retry rather than risking duplicates.
- One bottom playback slot: Preview replaces the local mini-player. Preview is streaming, capped at two minutes, seekable when source duration/range support permits. Local playback remains offline and retains Loop/Continue/Once and Fade 3s.
- Preview URL cache is RAM-only (up to 8 items, at most 60 seconds, shortened by source expiry), invalidated on network change/playback error. Cache lifetime is not playback duration. No prefetch of all results and no signed-URL/header persistence.
- Timing logs contain numbers/cache-hit only (`preview_resolve_ms`, `preview_start_ms`); device latency has not been measured here.
- Genuine embedded/source artwork cached privately for offline use; bounded image decoding and abstract fallback when source art is unavailable. Existing songs without cached artwork use fallback until artwork is available; no invented album cover.
- Artwork download has a 15-second elapsed deadline plus socket/byte limits, after durable audio completion. Android's embeddedPicture API materializes a byte array before the app can check its size: the cache/decode is bounded, but native extraction allocation is not guaranteed bounded.
- Confirmed Delete removes only the app-managed copy and associated library/history records, not the original imported file. Physical deletion is permanent; no false Undo.
- Additive Room migration 1 -> 2 adds artwork key and download jobs, retaining existing tracks and rename history. No destructive migration/reset.

## Install on the existing phone
1. Preserve any important originals separately. Source snapshots are not a backup of audio files or the phone database.
2. Use the ARM64 APK for Samsung M15. Install as an update over the existing Swararia Audio Lab test app. **Do not uninstall** or clear app data.
3. If Telegram provides `.zip.001` and `.zip.002`, download both into one folder and open/extract starting with `.001` in a split-archive-capable manager. Install the extracted `.apk`; the split parts are not APKs.
4. Android may request permission to install from your chosen file manager. Notification permission is optional; foreground work still follows Android restrictions.
5. Complete the device acceptance checklist below. Host tests do not establish phone success.

## Device acceptance (not executed on a physical phone here)
- Update keeps existing library and rename history. Play an existing track in airplane mode; verify rename and undo-last-rename still work.
- Search YT Music with both songs and Playlists; check actual titles, channel, duration, more results, sort and return navigation. Long press/select loaded tracks only.
- Start Preview while local audio is playing: local audio pauses, only one playback surface appears, seek progresses, cap is two minutes (or shorter source), Pause/Play/Stop work. Repeat the same track to exercise cache reuse. Background/lock/leave the page and check Preview stops immediately. Local music may continue in the background as before.
- Change network or force a stream failure; retry must resolve fresh metadata rather than loop forever on an expired URL. YouTube restrictions/anti-bot responses are a real external limitation, not proof of an app crash.
- Batch: fresh consent, actual per-file stages, completed files in Library, cancellation during transfer and during protected finalization, retry only failed files. Reopen after interruption: interrupted RUNNING becomes failed; queued work requires explicit Start download. No completed-file redownload.
- Artwork remains visible in airplane mode after a supported successful import/download; missing artwork uses abstract fallback.
- Delete an unused managed copy, then a currently queued/playing one; confirm file is gone, Library/player queue update, and imported original is untouched. Test failed storage permission/provider response without losing the library row on uncertainty.
- Check small screen, large text, keyboard/insets, long filenames/channel names and TalkBack labels. Native layout rendering has not been tested here.

## Evidence and limitations
Final build-host verification: all 18 offline gates passed, including 74 Gradle unit tests (zero failures/errors/skips), both ABI APKs, signature/native-payload compatibility and generated-schema comparison. Lint reports 31 warnings, zero Error/Fatal. Independent UI and final data/native reviews passed after focused fixes. Physical-phone execution remains unverified.

See `verification/checks-0.4.0.json`, `verification/update-compatibility-0.4.0.json`, `verification/upgrade-0.4-review.json` and the generated `dist/manifest-0.4.0.json`. Earlier failed-review JSON files are retained as history, not the final verdict.

The source toolchain/dependencies are reused; nothing new is installed. Application ID and signer remain unchanged. APKs from 0.3 are preserved and payload bytes for native libraries/bundled engine are compared directly.

Native ingest still requires 4 KB device pages; five bundled ARM64 FFmpeg/WebP libraries do not satisfy 16 KB alignment. Exact GPL native Corresponding Source/build provenance and public-distribution compliance remain unresolved. This is not a production or public-release clearance.

## Recovery
The pre-upgrade source checkpoint is `.checkpoints/before-0.4-source.zip` with SHA recorded in `verification/upgrade-0.4-baseline.json`. Restore source into a separate directory or revert the scoped code changes after preserving newer work; do not hard-reset unrelated files.

APK downgrade from versionCode 4 is not a safe database rollback: the older APK does not understand schema 2, and Android may refuse downgrade. Do not uninstall/clear data to force it. Source/APK rollback cannot recover physically deleted audio; use separate original-file backups. No device database restore has been tested.
