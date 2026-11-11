# Architecture foundation refactor

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
