# Architecture

## Android Remote Server Mode, Web Client & SHA-256 Media Sync (0.6.0 / Room v4)

`AppContainer → AndroidRemoteServerController → AndroidRemoteServerEngine + AndroidRemoteHttpListener + AndroidRemoteTlsIdentity ↔ PlaybackService (RemoteServicePlaybackHost) + MediaLibrary (Room v4)`

`AppViewModel (remoteServerState / remoteServerActions + deletedTrackChecker) → RemoteTabScreen → { DesktopRemoteScreen | RemoteServerScreen }`

- **Dual-plane protocol isolation:** `AndroidRemoteHttpListener` serves native Android remotes on `/v1/*` (Bearer token + rejecting browser `Origin`/`Referer`/`Sec-Fetch-Site` headers, schema-compatible with `DesktopRemoteJson`) separately from browser FOH remotes on `/` and `/web/*` (`__Host-swararia_session` `HttpOnly; Secure; SameSite=Strict` cookie + `X-Swararia-Csrf` memory token + strict same-origin `POST` enforcement + CSP without `innerHTML`/`localStorage`/`sessionStorage`). HTTP/1.1 parser rejects `Transfer-Encoding`, duplicate `Content-Length`, query strings, path traversal, and mismatched `Host` headers.
- **Ephemeral TLS without keystore files:** `AndroidRemoteTlsIdentity` generates an in-memory EC P-256 self-signed X.509 v3 identity (with `IP SAN`, `BasicConstraints cA=false`, `KeyUsage digitalSignature`, `EKU serverAuth`) bound exclusively to the chosen private/loopback IPv4 address.
- **Playback & transfer coordination:** `AndroidRemoteServerController` lives in `AppContainer` (process/service lifetime rather than Activity/ViewModel teardown), attaches to `PlaybackService` on the Main Looper (`RemoteServicePlaybackHost`), and caps concurrent heavy streaming transfers with `Semaphore(2)` inside 16 socket slots so FOH control polls/commands never starve. `MediaTransferEnabled` defaults `false`, `pauseTransferDuringPlayback` defaults `true`, and `view_only` devices are rejected from both control commands and media transfer.
- **Duplicate-free sync (`3.json → 4.json`):** `AudioMigrations.MIGRATION_3_4` adds nullable indexed `sha256` on `tracks`, populated on import/download and backfilled by `MediaLibrary.scanMissingMetadata()`. `MediaLibrary.importValidatedUpload` verifies staged upload digest, inspects audio tracks via `MediaExtractor`/`MediaMetadataRetriever`, deduplicates by `(sha256, sizeBytes)` before `MediaStore` `IS_PENDING 1 → 0` publication, and optionally links the existing or newly created track into the target playlist.

## Desktop Remote client (0.5.0 candidate)

`AppContainer → DesktopRemoteApi/DesktopRemoteHttpsApi + DesktopRemoteSessionStore/AndroidKeystoreDesktopRemoteStore → AppViewModel/DesktopRemoteSession → DesktopRemoteScreen (+ DesktopRemoteQrScanner)`

- Reuses existing manual DI, caller-owned ViewModel scope, identity-aware lifecycle guards, StateFlow, Compose components/icons/theme, Jackson and HttpsURLConnection. Concrete networking (`DesktopRemoteHttpsApi`) and Keystore persistence (`AndroidKeystoreDesktopRemoteStore`) are constructed only at `AppContainer`, never inside `AppViewModel`.
- Protocol v1 spans playback control (`play`/`pause`/`stop`/`fade_out`/`prev`/`next`), privacy-safe catalog browsing (`GET /v1/tracks`, `groups[]`, `active_group_id`, up to 250 sanitized tracks / 64 groups, bounded `98_304`-byte response ceiling) and `select_track` / `select_group` commands. `DesktopRemoteHttpsApi` tracks the request-scoped `SSLSocket` inside `DesktopRemoteSocketFactory` and closes it directly alongside `disconnect()` on cancellation or 8-second total deadline so in-flight HTTP header/body reads unblock promptly.
- `DesktopRemoteQrScanner` wraps Google Code Scanner (`play-services-code-scanner:16.1.0`) with `barcode_ui` manifest metadata and zero `CAMERA` permission; `DesktopRemoteScreen` buffers the scanned string across Play Services delegate `ON_PAUSE -> ON_RESUME` transitions in Compose memory (`pendingScanned`) so `DesktopRemoteSession.connect` stays decoupled from scanner hardware and only executes while foreground-visible.
- Protocol/strict pairing/JSON, request-scoped pinned transport, session orchestration and rendering are separate. Existing SearchSession, Room storage, Media3/Preview service and native payloads remain owners of their original work. One-time pairing `invite` secrets are never written to disk and never enter `SharedPreferences`, `SavedStateHandle`, `rememberSaveable`, Room, or logs.
- Approved active grants (`endpoint`, TLS SHA-256 `pinBytes`, `deviceId`, bearer `token`, `expiresAt`) are encrypted with Android Keystore (`AES/GCM/NoPadding`, hardware/OS CSPRNG IV, versioned AAD) via `AndroidKeystoreDesktopRemoteStore` inside `noBackupFilesDir` (excluded from cloud/ADB backups). `AppViewModel.onCleared()` invokes `desktopRemote.release()` (clearing RAM & polling without erasing the Keystore record) so reopening the app after full close/process kill auto-reconnects seamlessly (`fresh = false, role = null`, keeping `canControl == false` until `GET /v1/session` confirms live state). Explicit `disconnect()`, expiry, PC revocation (`AUTH`), PC certificate change (`TRUST`), or record tampering fail closed and delete the encrypted record.
- Remote only commands the desktop output. Foreground visibility controls read polling, not audio ownership; owner replacement/disposal cannot resurrect a stale session or send Stop. Mutations serialize with generation/permission guards, and ambiguous POST results are never replayed.
- Bounds include private IPv4 endpoints, explicit certificate fingerprint/validity/IP SAN, no global SSL/cleartext override, bounded body/idle/total request duration and cancellation cleanup. Actual Kotlin-to-Desktop HTTPS probes are host/JVM evidence, not Android/Windows or physical audio evidence. See `UPDATE-0.5.0.md` and final versioned verification reports.

## Local Library Grouping, Metadata Extraction & Playlists (0.5.0 / Room v3)

`LibraryRules / UiPresentation (pure JVM) ← LibraryScreen / SwarariaDialogs (UI) ← AppViewModel + PlaylistUiActions ← AudioLibraryRepository → MediaLibrary → Room v3 (AudioDatabase + AudioMigrations)`

- **Schema migration (`2.json → 3.json`):** `AudioMigrations.MIGRATION_2_3` adds nullable `artist`, `album`, `genre`, `metadataCheckedAt` columns to `tracks`, plus `playlists` (`name` unique index) and `playlist_tracks` (`playlistId`/`trackId` composite PK with `ON DELETE CASCADE` foreign keys and covering indexes). Existing `MIGRATION_1_2` constants stay unchanged.
- **Metadata extraction boundary:** `MediaLibrary` owns `MediaMetadataRetriever` inspection via `inspectAudioMetadata(uri)` during import (`publish`), download completion (`publicationStore.finish`), and lazy startup backfill (`scanMissingMetadata()` launched in `AppViewModel.init`), never inside Compose/UI or pure JVM presentation helpers.
- **Pure domain/grouping rules:** `LibraryRules` (`validatedPlaylistName`, `sanitizedTag`, `audioFormatLabel`) and generic `UiPresentation.groupItems` / `trackSubtitle` / `groupSummary` remain Android/Room-free and covered by host JUnit tests (`test_ui_rules.py`, `LibraryRulesTest`, `UiPresentationTest`).
- **Queue scoping:** Playing a track inside `Playlist`, `Artis`, `Album`, or `Format` mode delegates that group's track list via `onPlayQueue` to `MainActivity.playTrack(track, queue)` so `MediaController` advances within the active group without bypassing `PlaybackDeletion.canPlay` checks.

## Current Preview ownership (0.4.1 candidate)

`AppViewModel/SearchSession → PreviewPlayback/PreviewRemote → app-only MediaSession commands → PlaybackService/PreviewSession → AudioSearchRepository`

- Search/pagination stays ViewModel-scoped. Preview resolution and the single ExoPlayer are service-scoped; controller release/Compose disposal must not cancel service work.
- Preview temporarily borrows the local player, clips media to 120 seconds, forces repeat off, and restores the saved local queue/position/mode paused after stop/end/error. Explicit deleted IDs are removed from saved context before durable deletion.
- Only public video identity/title, phase, request identity and sanitized failure state cross session metadata. Signed source/header stay in ephemeral repository/service RAM and are not placed in controller MediaItem URIs or logs.
- Numeric timing distinguishes engine initialization, native execution, source resolution and prepare/ready/playing. Host tests/source contracts/API compile are not device lifecycle or latency evidence. See `UPDATE-0.4.1.md`.
- No dependency, persistence schema or native payload change. The following foundation-refactor sections are historical evidence, not a claim that all later source stayed byte-identical.

## Historical architecture foundation refactor

Status: implementation, local integration checks and independent final review passed. Behavior-preserving refactor before the approved visual redesign. Baseline `e96b931e2214006dfb956a004f662b895dc77340`, branch `refactor/architecture-foundation`. Recovery snapshot: `.checkpoints/before-architecture-refactor.zip`; source and untracked mockups preserved. No new packages, database migration, app ID/version/signing/engine change, UI upgrade, Delete, batch queue, artwork persistence, streaming optimization or GitHub publication.

## Composition and dependency direction

`SwarariaApp → AppContainer → repository implementations + Room + service-state adapter`

`MainActivity → AppViewModelFactory → AppViewModel → SearchSession → AudioSearchRepository`

`Screens/components ← immutable/read-only state + callbacks`

- AppContainer is the platform composition root, using existing dependencies and manual constructor injection. Only this boundary adapts process-local IngestStatus into presentation dependencies. Native service owns its own work, notification and cleanup.
- AppViewModel is a lifecycle/presentation adapter, not a context or repository factory. It delegates search/preview orchestration, retains library observation/rename behavior, and bridges UI messages.
- SearchSession owns search/preview state, generation tickets, pagination/retry context and cleanup gating. It is Android-free and tested with injected repositories and an explicit scope. Commands are confined to the owning UI thread; callers must not dispatch concurrently from arbitrary threads. Scope lifetime belongs to the caller; close cancels session work, not sibling work.
- AudioSearchRepository exposes metadata pages and validated preview media. YoutubeSearchRepository owns native/network execution, bounded enrichment, plugin setup and process cleanup. Fake implementations in tests do not replace or claim native execution.
- AudioLibraryRepository exposes presentation-required capabilities; MediaLibrary retains all storage operations. Room entities/DAO/database declarations are isolated in AudioDatabase.kt without changing their package names, schema identity, database filename or native storage method bodies.
- OnlineWorkRules remains the pure exclusion policy. Activity permission/picker callbacks check the session's current state again, not a previously rendered enabled flag. Repository rechecks ingest at the I/O boundary.

## Behavioral invariants
- Cancellation retains busy state until native work cleanup finishes; generation tickets separately prevent stale results. Cancelling twice must not prematurely unlock another operation.
- Retry keeps query/provider/kind/sort/playlist and page offset even when no complete result has been accepted. Paging remains capped at 50 with deduplication.
- Preview resolves a stream without publishing library files. Its native player/lifecycle behavior is not redesigned by this refactor. Audio focus, loop/once/continue and fade service logic remain unchanged.
- Rename/import/publication compensation remains unchanged. File storage and Room are distinct systems; this refactor does not make them a single atomic transaction.

## Tests and engineering rules
- Baseline full Gradle suite: 23 tests passed before edits. Ten additional Android-free behavioral session tests exercise result delivery, scoped retry, pagination, cancelled/late completion, cleanup gating, ingest exclusion, preview failure and scope ownership.
- Source/asset contracts check wiring/resources only, not runtime behavior. Device audio/lifecycle/UI validation remains separate; historical phone feedback does not cover a new refactor automatically.
- Work in small tested changes. Keep UI drawing separate from repository/file operations. Do not add abstractions with no actual consumer, migrate storage merely to move files, or add optional frameworks for appearance.
- Verify unit tests, compiler, lint, APK contents/signature, schema/native/engine/old-APK/mockup preservation and independent review before committing. No automatic push.

## Presentation boundaries

- SwarariaUi.kt is the route/state/dialog owner (124 lines after extraction, previously 1082); its public signature and SaveableState/navigation structure are retained.
- LibraryScreen.kt contains library filtering, rows and local editor controls. SwarariaDialogs.kt contains live rename/history/settings/diagnostics dialogs. SwarariaPlayer.kt contains the existing MediaController presentation adapter, playback observation and player/queue UI.
- UiComponents.kt contains shared BrandLogo, TrackArtwork, GlyphButton and EmptyMessage. Basic controls are stateless; screen-local menu/editor/seek state stays local rather than being hoisted gratuitously.
- AddAudioScreen, preview lifecycle player and playback-mode controls are byte-identical to baseline. Twenty-eight active UI declarations have matching normalized hashes against baseline; theme/font declarations are preserved. This is source-level preservation, not a claim of native pixel/device validation.
- Dead legacy PlayerScreen, OrganizerScreen and old add/ingest UI were removed after repository reference audit. Packaging/source tests now verify active UI, not dead class names. The unused root onSearch argument remains for API compatibility in this bounded refactor.
- Keep file/package/class names of persistence models stable; this single-module extraction deliberately does not introduce Gradle modules, a DI framework or broad package renames. Consider those only when actual feature/team boundaries justify their overhead.

## Remaining boundaries
Implementation, 33 Gradle unit tests, 15-warning/zero-error lint, both APK builds, APK contracts, signer/native/schema/storage/mockup preservation checks have passed. Independent final review passed with no concrete security/logic findings (`verification/architecture-review.json`); handoff remains local with no automatic push. The service-state bridge is still process-local, not a durable batch queue. Native execution/device tests, CI, recovery after process death, performance measurements and old native 16KB/GPL provenance limits are not magically solved by dependency injection. Upcoming UI/batch/Delete/artwork work requires its own implementation plan and tests; approved mockups are not an implementation authorization for this stage.
