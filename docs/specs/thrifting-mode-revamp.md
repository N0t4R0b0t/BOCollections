# Thrifting mode revamp

## Problem

Thrifting mode (`ThriftController`/`ThriftService`/`ThriftingPage`) shipped whole-cloth in the
initial commit as a partial realization of its own design in `STRETCH_GOALS.md` §2, and hasn't
been touched since. Capture is manual single-shot (point, tap a shutter button, wait) rather than
hands-free; the "taste profile" layer described in the original doc was never built, so
`OwnedStatus` only has three tiers (OWNED/DIFFERENT_VERSION/NOT_OWNED) — there's no way to
surface "you don't own this, and it looks like something you'd want." Every scan is stateless —
nothing is remembered after the response is shown, so there's no way to answer "have I seen this
before, and where." Separately, bulk-scan-mode's recent overhaul deleted the single-item scanner
that `ThriftItemCard`'s "Scan to add" button still points at, breaking that integration.

The underlying problem this revamp solves: a collector standing in front of a shelf at a thrift
store, record fair, or flea market is presented with more items than they can individually
evaluate, and will inevitably miss things they'd want. The app should narrate what it recognizes
hands-free, flag genuine finds (not just "you don't own this" but "this looks like your taste"),
and remember the trip so a "did I see that there before" question can be answered later.

## Goals

- Continuous, presence-gated camera capture with a live narrated status feed ("I see X… you
  already have this" / "I see Y… this isn't in your collection"), matching bulk-scan-mode's
  capture UX — not the current manual tap-to-capture.
- Two explicit, manually-toggled capture modes (no auto-detection):
  - **Shelf mode** — continuous multi-item identification with bounding boxes, the existing
    `ThriftService` shape, revamped to fire on sustained presence rather than a manual shutter tap.
  - **Held-item mode** — single item in hand, reusing bulk-scan-mode's barcode/OCR identification
    approach (adapted as its own hook — `useCaptureLoop` itself is too scan-session/draft-specific
    to reuse directly) to identify one item and narrate ownership/interest, without creating a
    scan-session draft.
- Consolidate `ThriftService`'s near-duplicate vision-call boilerplate (message-building,
  `extractJson`, response parsing) with `VisualScanService`'s equivalent instead of maintaining
  two copies of the same pattern.
- Improve cross-reference matching beyond today's blunt exact-case-insensitive-title match.
- Fix `ThriftItemCard`'s "Scan to add" action: instead of routing to the now-deleted single-item
  scanner, it creates/adds to a scan session with a draft pre-populated from what thrifting mode
  already identified (title, format, etc.) — no re-identifying from scratch.
- A 4th tier alongside OWNED/DIFFERENT_VERSION/NOT_OWNED — an "interesting" flag for not-owned
  items that align with the user's taste profile.
- A taste profile derived from structured fields already in the data model — category, format,
  publisher, and release-year-decade distribution across the user's owned collection. Cached as
  JSONB on `User`, recomputed lazily/synchronously when stale (the collection has changed since
  the cache was last built) — no RabbitMQ, no background job. (RabbitMQ is provisioned in this
  app but has zero consumers anywhere today; introducing the first one for a cheap aggregate
  recompute isn't justified.)
- Persistent thrift sessions: a session per shopping trip (optional free-text location, same
  pattern as `CollectionEntry.location`), holding every distinct item sighted during it — across
  either mode, at any tier (owned, not-owned, and interesting — not just the "finds", since a
  useful log records what was seen, not just what was missing).
- Search past sightings by title across a user's thrift history ("have I seen this movie in a
  store before"), reusing the same full-text-search convention `Item` already has.

## Non-goals

- Author/artist-affinity matching and embedding/semantic similarity scoring — a materially better
  "is this a good fit" signal than structured-field matching, explicitly deferred as its own
  future feature to fine-tune independently, not built now.
- Genre as a first-class tagged field — it isn't structured data anywhere in this app today, and
  building real genre tagging (likely AI-extracted) is out of scope for this pass.
- Auto-detecting shelf-mode vs. held-item-mode from the camera feed — an explicit manual toggle
  only; classifying "shelf of many spines" vs. "one held item" from raw frames is its own
  computer-vision problem, not worth solving when a toggle costs nothing.
- Real geolocation/maps integration for session location — a simple optional free-text field
  (mirroring `CollectionEntry.location`), not GPS/maps.
- RabbitMQ-based async taste-profile refresh (see Goals — deliberately synchronous/lazy instead).
- Changing how `VisualScanService`'s barcode/lookup/vision plumbing works for bulk-scan-mode
  itself — held-item mode reuses that *approach*, not by modifying bulk-scan-mode's own flow.

## User stories

- As a collector browsing a shelf, I want the app to continuously narrate what it recognizes
  without me tapping anything, so I can keep scanning shelf after shelf hands-free.
- As a collector, I want to hold up a single item and have the app tell me if I already own it or
  if it looks like a good find, the same way bulk-scan mode identifies a held item.
- As a collector, when the app flags a not-owned item as a good find, I want a quick way to start
  adding it to my collection without re-identifying it from scratch.
- As a collector, I want every store visit logged with what I saw, so that months later I can
  search whether I've seen a particular title in a store before.
- As a collector, I want the app to flag items that aren't just "not owned" but actually look like
  something I'd want, based on what's already in my collection.

## Acceptance criteria

- Starting a thrift session requires picking a mode (shelf or held-item) up front; switching modes
  mid-session is a deliberate action, not automatic.
- Shelf mode auto-fires a vision identify-and-annotate call once `usePresenceDetector` reports
  sustained presence — no manual shutter tap required.
- Held-item mode identifies a single item via barcode-decode/OCR racing (bulk-scan-mode's
  approach, reimplemented for this narrower purpose) and narrates ownership/interest via the
  status feed instead of creating a draft.
- Every distinct item identified in either mode, at any tier, is persisted as a sighting tied to
  the current thrift session; the session itself is created when a trip starts and closed when
  the user ends it (open/closed semantics, same shape as bulk-scan-mode's scan sessions).
- A list of past thrift sessions and a title search across all past sightings are both reachable
  from the UI.
- `OwnedStatus` gains a 4th value for not-owned items that align with the user's cached taste
  profile (category/format/publisher/decade match against the collection's distribution).
- Taste profile is cached as JSONB on `User` and recomputed synchronously when stale — never via a
  queued/background job.
- "Scan to add" creates or reuses an open scan session and pre-populates a draft from the
  identified fields, rather than navigating to a route that no longer exists.
- `ThriftService`'s vision-call plumbing is consolidated with `VisualScanService`'s rather than
  remaining a separate copy of the same pattern.
- Cross-reference matching handles at least basic normalization (whitespace/punctuation trimming)
  beyond a raw exact-title comparison; still no author-affinity or embeddings (see Non-goals).

## Open questions

- Exact taste-profile scoring/threshold for what counts as "interesting" — left for the technical
  plan.
- Exact mode-toggle UI (segmented control, tab, two routes) — left for the plan.
- Sighting dedup granularity — if the camera passes over the same shelf section twice in one
  session, does the same title get logged twice? Likely dedupe within a session similar to
  bulk-scan-mode's same-session-barcode-duplicate flagging, but the exact rule is for the plan.
- Whether sightings store a photo/crop (reusing bulk-scan-mode's `StorageService` abstraction) for
  later visual reference, or stay text/metadata-only — leaning toward storing at least a
  reference image, but left for the plan.
- Whether a thrift session can be reopened after being closed, or closing is terminal — left for
  the plan; likely mirrors scan-session's non-destructive open/closed model.

## Existing state

Thrifting mode was authored complete-but-partial in the single initial commit (`bcde085`) and has
never been touched since — this isn't a mid-build stall, it's a shipped v1 that stopped short of
its own spec (no incremental git history to recover; `git log --grep=thrift` returns nothing).

- `ThriftController`/`ThriftService`/`dto/thrift/*` — **kept and reworked**, not discarded: prompt
  updated for the continuous/taste-aware flow, cross-reference logic improved, vision-call
  boilerplate consolidated with `VisualScanService`. `ThriftService` today does *not* reuse
  `VisualScanService` at all — it's an independent copy of the same "build `UserMessage`+`Media`,
  call Ollama, `extractJson`, parse" pattern.
- `OwnedStatus` (`OWNED, DIFFERENT_VERSION, NOT_OWNED`) — **extended** with a 4th value, not
  replaced.
- `ThriftingPage.tsx` — **substantially reworked**: manual single-shot capture (`LIVE → SCANNING →
  RESULTS` phase machine, shutter button) replaced by continuous presence-gated capture with a
  mode toggle.
- `ThriftResultOverlay.tsx` (canvas-based bbox rendering + tap-to-select) and `ThriftItemCard.tsx`
  (bottom-sheet detail card) — **likely reused as-is** for shelf-mode's result presentation, since
  the underlying "many items with bounding boxes" shape doesn't change; the plan should confirm.
- `ThriftItemCard.tsx`'s "Scan to add" button (currently navigates to the deleted `/scan`
  single-item route) — **fixed** to integrate with scan sessions instead.
- `usePresenceDetector` (from bulk-scan-mode) — **reused directly**, no changes needed; it's
  already generic (takes a `videoRef`, returns `{ present, start, stop }`, no scan-session
  coupling).
- `useCaptureLoop` (from bulk-scan-mode) — **not reused directly**; it's a reducer wired
  specifically to barcode/`MatchKind`/draft-creation semantics. Only its overall pattern
  (presence-driven phase transitions composed with `useCamera`) is a reference for a new,
  thrift-specific capture hook for held-item mode.
- `User` entity — **gains a new JSONB taste-profile column**; no such field exists today.
- Thrift session/sighting persistence — **entirely new**; today's `ThriftScanResponse` is
  request/response only, nothing is ever saved.
- RabbitMQ — provisioned (`docker-compose.yml`, `application.yml`) but has zero consumers
  anywhere in `backend/src/main/java` today; **remains unused** — explicitly not adopted here.
