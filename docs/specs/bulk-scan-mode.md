# Bulk scan mode

## Problem

Today's `/scan` flow (`ScannerPage`) handles one item at a time and requires a manual
confirmation tap after every successful lookup (pick a collection, tap "Confirm add") before the
barcode detector resumes. For someone digitizing a shelf or a box of media, that's dozens of taps
for what should be a "hold item up, put it down, next" motion. The guided-capture (no-barcode)
path is even more manual — the user has to notice there's no match, tap into guided capture, and
walk through it by hand.

This feature replaces that flow with a session-based, largely hands-free capture mode: the user
picks a collection, then continuously presents items to the camera while the app identifies each
one, drafts it with minimal-to-no input, and narrates what it's doing via a live status feed. A
separate review step at the end (which may happen on a different device, at a different time) is
where the user actually approves, edits, or discards what got drafted.

## Goals

- Continuous camera loop: after picking a target collection, the user presents items one after
  another with no per-item tap required for the common (confident barcode match) case.
- Live status feed narrating state: "Nothing in frame" → "I see something, show me the barcode" →
  lookup/verify/OCR progress → "Drafted: XYZ" → "Moving on to another item."
- Three identification signals run concurrently per item rather than one linear wait: presence
  detection (new), barcode decode (existing `useBarcodeDetector`), and OCR/vision-based
  identification (existing `/scan/extract` machinery, but auto-triggered once presence is
  sustained rather than gated behind a manual "no barcode" button).
- Confident match (barcode found + `verify` passes) → auto-draft from online metadata/cover art,
  no guided multi-angle capture required, immediately ready for the next item. A single reference
  photo of the actual physical item is still captured automatically from the live feed (reusing
  the frame already taken for visual verification) and attached to the draft alongside the online
  cover art — no extra tap or angle required to get it.
- Mismatch (likely a different edition/variant — e.g. a retailer-exclusive cover) or no online
  match at all → flagged as a variant/unmatched, routed into guided capture for the user's own
  photos. The app does not attempt to identify *which* specific variant it is; the user annotates
  that at review time (e.g. adding a note like "Dollar General Special Edition").
- Item-transition handling: fully automatic, driven by presence changes (item removed from frame,
  or a new/different item appearing) — the user drives pacing simply by watching the status feed
  and physically moving on whenever they're satisfied, not by tapping anything. The moment a
  transition is detected, whatever draft can be built from the current item's progress is
  finalized immediately:
  - Confident specific-copy match (barcode + verify passes): draft uses the real online
    metadata/cover, as today.
  - Content recognized but not a confirmed specific copy (e.g. OCR/vision identifies "this is the
    movie XYZ" but no barcode match, or a mismatch flagged a variant): the app asks the user, via
    the status feed, to present different angles/features so it can build an accurate record of
    their actual copy. This request is never a gate — if the user moves on before providing those
    shots, a draft is still created using whatever content-identification succeeded, filled out
    with generic/stock metadata and imagery from the closest online match, and flagged so it's
    visibly distinguishable at review time (i.e. "these aren't your actual item's photos").
  - No content identification at all: a minimal draft is still created from whatever raw
    signal exists (e.g. a barcode string with no lookup match), for the user to fill in by hand at
    review.
  - A manual "skip to next item" affordance may still exist as a convenience, but it is not
    load-bearing for correctness — an imprecise automatic transition call just yields a weaker
    draft, not a failure, and is cheap to fix at review (edit, merge, or discard).
- Already-owned duplicates (barcode matches something already in the target collection): skipped
  silently, status feed notes it, no draft created.
- Scan sessions persist server-side, tied to the user's account: a session (collection + status +
  its drafts) can be paused and resumed later — either by continuing to scan more items into it,
  or by reviewing/approving its drafts — from any device. E.g. scan on a phone, review on a
  desktop, days apart if needed.
- A session management view listing the user's sessions (open/completed, collection, draft count,
  last updated) with actions: resume scanning, review drafts, discard session.
- A review screen per session: approve, edit, or discard each draft before it becomes a real
  `Item` + `CollectionEntry`. Also supports merging two drafts into one — the backstop for cases
  where the automatic (imprecise, by design — see Non-goals) item-transition detection wrongly
  splits a single physical item into two drafts.
- At most one item is being actively identified/processed per session at a time. If the user
  presents a new item while the previous one's lookup/verify/extract call is still in flight, the
  new item's processing waits its turn rather than racing the in-flight call; the status feed
  reflects this ("still working on the last one…") so the pacing promise doesn't silently break
  down under fast physical presentation.
- Photo/image storage: drafts and their captured photos are persisted through a small storage
  abstraction (not a third-party framework — see Existing state) that can write to local
  filesystem (dev/self-hosted default) or S3-compatible/CDN-backed remote storage, selected via
  configuration/profile, mirroring how other optional external credentials in this app already
  work. The database stores only a reference (path/URL) to each photo, same shape as `Item.coverUrl`
  today — no image bytes in Postgres.
- Fully replaces the existing single-item `ScannerPage` flow (see Existing state) — this is not an
  additive second scan mode.

## Non-goals

- Automatically identifying *which* specific regional/retailer/pressing variant an item is beyond
  flagging that it doesn't visually match the online record. Resolving that is a manual annotation
  step at review time.
- Guaranteeing *accurate* item-transition detection on the no-barcode path (i.e. always correctly
  distinguishing "same item, different angle" from "a genuinely different item" via vision alone).
  The app makes a best-effort presence-driven call and finalizes a draft either way; occasional
  wrong calls are an accepted tradeoff, not a bug to eliminate, since the review screen is the
  correctness backstop.
- Changing the external metadata sources or vision model used — this reuses the existing lookup
  chain (Open Library / Discogs / MusicBrainz) and Ollama vision plumbing (`VisualScanService`,
  `MetadataLookupService`) as-is.
- Wiring TMDB into the lookup chain — it remains unwired dead code, out of scope for this feature.
- Adopting a third-party content-storage framework (e.g. Spring Content) — it was considered but
  is an archived/unmaintained project as of Feb 2026, so a small hand-rolled storage abstraction is
  used instead (see Goals).

## User stories

- As a collector digitizing a shelf of CDs, I want to hold each one up to my phone's camera in
  sequence and have the app auto-draft the ones it recognizes, so I don't have to tap anything
  between items.
- As a collector, when the app can't find or confidently confirm an online match, I want it to
  tell me clearly what it needs ("show me the barcode," "flip to show the spine") so I know what
  to do without guessing.
- As a collector, I want to stop scanning partway through a box, close the app, and pick the
  review step back up later on my laptop, without losing what I already scanned.
- As a collector, when the app flags an item as a possible different edition than what it found
  online, I want to add my own note about that at review time rather than have the app guess.
- As a collector, I want already-owned duplicates to be skipped automatically so I don't end up
  with accidental repeat entries from re-scanning the same item.

## Acceptance criteria

- Starting a scan session requires selecting a target collection first; the collection is fixed
  for the life of the session.
- While scanning, the UI shows a persistent status line reflecting current state (idle/presence/
  looking up/verifying/extracting/drafted/moved on), updated without user action.
- A barcode decode that resolves to a confident match (found + `verify.matches == true`, or
  `verify` unavailable but source ≠ NOT_FOUND with no visual mismatch signal) creates a draft
  automatically — no confirmation tap — and the loop resumes scanning immediately.
- A barcode decode with no online match, or a visual mismatch, transitions into guided capture for
  that item; the resulting draft is marked as unmatched/possible-variant.
- If the user moves on (presence changes) before guided capture is completed, a draft is still
  created immediately using whatever was recovered — recognized title/content plus generic/stock
  metadata and imagery from the closest online match — and is visibly flagged in the review UI as
  using generic (not user-captured) imagery.
- An item with no decodable barcode, once presence is sustained past a short threshold, triggers
  OCR/vision-based identification (`/scan/extract`) without requiring the user to tap into a
  "manual capture" mode first.
- A barcode match against an item already in the target collection produces no draft; the status
  feed shows a "skipped — already in this collection" message.
- Every created draft includes at least one auto-captured photo of the actual physical item from
  the live feed, regardless of match confidence — captured passively (no dedicated tap or guided
  step) by reusing a frame already taken during identification/verification.
- Drafts are persisted server-side as part of a session record, associated with the user, and
  survive the browser/app being closed.
- The sessions list view shows all of a user's scan sessions with status, collection, draft count,
  and last-updated time, and supports: resume scanning, open review, discard session.
- Resuming a session (from any device/session) adds new drafts to the same session record; it does
  not create a new session.
- The review screen lists every draft in a session and lets the user approve (creating the real
  `Item`/`CollectionEntry`), edit fields first, discard it individually, or merge two drafts into
  one (keeping the photos/fields from both, user picks which fields win on conflict).
- Photos captured during a session (auto reference shots and guided-capture angles) are persisted
  via the storage abstraction and remain retrievable after the session/browser is closed, the same
  way persisted draft data does.
- The old `ScannerPage` single-item flow, its route, and `ScanResultCard` are removed once the new
  flow covers its functionality; no dead route is left behind.

## Open questions

- Exact presence-detection technique (frame-diffing heuristic vs. something else) — left for the
  technical plan; needs to be cheap enough to run continuously client-side.
- Exact heuristic/threshold for "sustained presence" before auto-triggering OCR, and for the
  no-barcode "moved on" nudge timing — left for the technical plan, likely to need tuning after
  trying it live.
- Whether a session can ever be reopened after being fully reviewed and explicitly closed, or
  whether "closed" is terminal — leaning toward sessions simply staying open until the user
  explicitly discards/closes them (no forced resolution of every draft), but exact close semantics
  are for the technical plan.
- Whether "possible variant" drafts need a distinct visible flag/badge in the review UI versus a
  plain unmatched draft — likely yes, but exact presentation is a plan/implementation detail.
- Whether to detect an item scanned twice *within the same session* (not already in the
  collection, but already drafted earlier this session) and warn/dedupe, versus letting it through
  since a collector may legitimately be adding two copies.
- Whether silently-skipped already-owned duplicates should be surfaced anywhere recoverable (e.g. a
  lightweight "skipped this session (add anyway)" list) rather than a pure log line, so an
  intentional second copy isn't simply lost with no path back.
- Whether the OCR/vision hint (previously user-typed in the old guided-capture flow, e.g. "this is
  a vinyl record") should default to the session's collection `primaryCategory` when set, to avoid
  losing accuracy now that the step is automatic rather than user-initiated.
- Behavior on a lookup/vision-call failure mid-item (network error, Ollama unavailable) — retry,
  fall back to a minimal/manual draft, or surface an error in the status feed — left for the
  technical plan.
- Whether a session should auto-pause after a period with nothing in frame (to save battery/avoid
  holding the camera open indefinitely) or stay active until the user explicitly leaves — left for
  the technical plan.
- This spec assumes single-item framing (one item at a time in the camera's field of view,
  consistent with today's centered reticle) — multiple simultaneous items in frame is not handled
  and is out of scope.

## Existing state

Code found during Stage 0 (no prior spec or stalled implementation — this is being replaced
because it's incomplete for the new goal, not because it was abandoned mid-build):

- `frontend/src/pages/ScannerPage.tsx` — owns a linear phase state machine (`SCANNING` →
  `LOOKING_UP` → `CONFIRMING`/`NO_MATCH` → `GUIDED_CAPTURE` → `EXTRACTING` → `REVIEW_DRAFT`) in
  local component state, one item at a time, with a manual confirm tap. **To be discarded** and
  replaced by the session-driven flow.
- `frontend/src/components/scanner/ScanResultCard.tsx` — single-item confirm card.
  **To be discarded.**
- `frontend/src/components/scanner/CameraPreview.tsx`, `GuidedCapture.tsx`,
  `frontend/src/hooks/useCamera.ts`, `frontend/src/hooks/useBarcodeDetector.ts` — **reused as-is**;
  the barcode detector already runs a continuous, debounced, tap-free decode loop, which is most of
  the "hands-free" mechanism this feature needs.
- `backend/.../controller/ScanController.java` — `GET /scan/barcode/{barcode}`,
  `POST /scan/verify`, `POST /scan/extract` — **reused as-is**, all single-item/stateless today.
  Session/draft orchestration is new and sits on top of these, not inside them.
- `MetadataLookupService`, `VisualScanService`, `OpenLibraryService`, `DiscogsService`,
  `MusicBrainzService` — **reused as-is**.
- No `DRAFT` status, session entity, or batch concept exists anywhere in the schema
  (`V1__init_schema.sql`) or entities (`Item`, `CollectionEntry`, `Collection`) today — this is
  **new**: a scan-session entity and draft-item entity/status need to be designed in the technical
  plan.
- No image/photo storage exists anywhere in this app today. `/scan/verify` and `/scan/extract`
  accept base64 frames and analyze them statelessly — they never persist the image, and
  `Item.coverUrl` only ever points at someone else's externally-hosted image. Storing user-captured
  photos (one auto reference shot per draft minimum, more for guided capture) is **entirely new**
  infrastructure: a small storage abstraction (local filesystem / S3-compatible, profile-switched)
  needs to be designed in the technical plan — not reused from anything existing.
- `TmdbService` exists but is unwired (dead code) — left alone, out of scope.
