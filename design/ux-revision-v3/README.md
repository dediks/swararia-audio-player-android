# Swararia UX revision v3 — proposed concepts

Status: rendered concepts for user review; NOT approved for native implementation.

## Scope and evidence
Based on the user's actual phone screenshots of search, playlists, preparation and competing preview/local players. Preserve dark/mint and original logo. Use existing Pillow/Inter/FFmpeg only; no packages installed. Android source, database, engine, signing and APKs are outside write scope. before.json covers 62 protected files; test_preview verifies byte identity.

## Exports
1. Search: compact search/source/type controls, one sort control, six visible example rows, one Preview playback surface.
2. Playlist selection: dedicated context, three checked example tracks, Select all/Clear and fixed Download (3).
3. Downloads: same three selected tracks, one complete, one transferring, one waiting; real production data must drive counts/progress. No pause/resume-download feature is promised.
4. Library/Delete: contextual destructive confirmation, identifies file and states physical deletion. Imported source remains untouched; do not promise Undo for permanent deletion.
5. Full player: local playback, timeline, previous/play/next, Loop track menu, Fade 3s and Queue.
6. Loading: animated skeleton/spinner in result area; Cancel stays available. No invented percentage or ETA.

All tracks, creator names, artwork, counts, clocks, playback times, sizes and progress are SAMPLE DESIGN DATA. Covers are abstract fallback concepts, not fetched album artwork. Proposed preview duration 120 seconds is an explicit proposal, not an accepted policy or a claim that playback was measured. Source access, startup time, seek behavior and expiration require engineering/device verification.

## Interaction and unpictured states
- Long press enters selection mode; checkboxes use large touch regions, not merely the drawn checkbox bounds. Select all affects loaded tracks only.
- Download (n) first opens batch consent, initially unchecked, for audio the user is authorized to download. Queue processing is sequential; completed files remain after cancel, one failure does not stop the batch, Retry failed affects failed items only. Unknown byte totals show indeterminate progress; processing/saving have separate short stages.
- Preview replaces the local mini-player surface, pauses local playback before audio starts, and never silently auto-resumes it. Connecting/Buffering/Playing/Paused/Finished/Failed states share one surface. Stop, navigation and app background clean it up. Retrying preserves the selected track/context.
- Type filter and source persist when navigating into/out of a playlist. Import/link/settings live in contextual/header actions, not after search results.
- Empty/no-results states use a short line and optional retry/reset, not a paragraph. Missing file/unknown duration/denied permission/invalid link/cancelled operation must remain honest and actionable. No account/favorites/sync features introduced.
- Delete touches managed app copies only, reconciles the player/queue and database with storage, and reports failure rather than silently removing only a row. Native implementation needs separate approval and safety tests.

## Verification and next gate
Six PNG screens inspected individually at phone-readable scale. Decode/count/dimension, GIF multi-frame, and protected source/APK hash tests passed. Minor corrections: remove duplicated sort action, remove misleading selection chevron, remove overlapping completion icon and avoid advertising unsupported download pause.

This is not an interactive prototype, native rendering, font-scaling validation, audio execution, performance measurement or accessibility certification. Only this versioned design directory was created. Removing this directory reverts the mockup stage; do not restore/reset app source. Next: user visual feedback/approval, then a separate implementation action plan covering UX, streaming optimization, safe delete and batch queue.
