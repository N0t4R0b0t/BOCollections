# VIDEO barcode lookup + universal barcode cache

## Problem

`MetadataLookupService.lookup(barcode, userId)` routes on barcode shape only: ISBN-looking
barcodes go to OpenLibrary, everything else goes to Discogs then MusicBrainz (both audio-only).
There is no path for VIDEO items at all — a DVD/Blu-ray UPC falls into the "else" branch, fails
both audio sources, and returns `NOT_FOUND`. This forces every DVD in bulk-scan mode into the
guided front/back/spine capture fallback, even though `TmdbService.searchByTitle()` already
exists, fully implemented, and just isn't wired to anything (`docs/specs/bulk-scan-mode.md` marks
it explicit out-of-scope dead code). Separately, every barcode lookup — PRINT, AUDIO, and the new
VIDEO path — re-hits its external source on every scan, even for a barcode this exact instance has
already resolved before, which matters once VIDEO adds a rate-limited external dependency
(UPCitemdb's free tier: ~100 lookups/day, 1/sec).

## Goals

- Resolve a DVD/Blu-ray UPC to real metadata: UPC → UPCitemdb (new integration, product title) →
  `TmdbService.searchByTitle()` (already exists — just needs a caller) → full `LookupResult`
  (poster, description, release year).
- A generic, permanent `barcode → resolved metadata` cache, keyed by barcode, that every category
  (PRINT/AUDIO/VIDEO) checks *before* touching any external source — so a given barcode is only
  ever externally resolved once, system-wide, regardless of which user or collection scans it.
- Cache negative results (`NOT_FOUND`) too, so a barcode with no match anywhere doesn't re-spend
  rate-limit budget on repeat scans.

## Non-goals

- No per-user/per-collection cache scoping — this is a global, shared cache of barcode → product
  identity, which doesn't vary by who's asking (distinct from `CATALOGUE`, which stays a
  per-catalogue "do I already own this exact `Item` row" check and is unaffected by this change).
- No manual cache-invalidation UI/endpoint. A negative-cache entry that later gets a real match is
  expected to be rare enough not to warrant one; can be added later if it becomes a real problem.
- No IGDB/GAME barcode source — same "no barcode source for this category" gap exists for GAME,
  but wasn't part of this pitch and isn't being addressed here.
- No change to bulk-scan-mode's guided-capture fallback itself — it stays exactly as-is for
  whatever still comes back `NOT_FOUND` (obscure/regional DVDs UPCitemdb doesn't have).

## How it works

`MetadataLookupService.lookup()` gains a step between the existing catalogue check and the
existing external-source routing:

1. Own catalogue (unchanged).
2. **New:** global barcode cache lookup. Hit with a real result → return it (marking
   `ownedInCollections` per-user as today). Hit with a cached negative → return `NOT_FOUND`
   immediately, no external call. Miss → fall through to step 3.
3. External routing (extended, not replaced):
   - ISBN-shaped → OpenLibrary (unchanged).
   - Otherwise → Discogs → MusicBrainz → **new:** UPCitemdb (resolve a title) → **new:**
     `TmdbService.searchByTitle()` (resolve full metadata from that title). First success wins.
   - Whatever the outcome (a real `LookupResult` or exhausting every source), write it to the
     cache before returning — a success as a positive entry, an exhaustion as a negative one.

Barcode shape still can't tell us "this is a DVD" vs. "this is a CD" up front (UPC-A is used by
both) — so rather than adding category-guessing, the fix is to simply extend the existing
try-in-sequence chain with the new VIDEO-capable sources as an additional fallback tier, matching
how the chain already tries Discogs before MusicBrainz. This means the audio sources still get
first crack at a barcode (no regression there), and UPCitemdb/TMDB only get tried once those two
have already failed.

## User stories

- As a collector scanning a DVD in bulk-scan mode, I want the app to recognize it online (cover,
  title, release year) the same way it already does for books and CDs, instead of always falling
  back to manual guided capture.
- As a collector re-scanning a barcode that anyone has already resolved before (in my catalogue or
  not), I want that lookup to be instant and not depend on a third-party rate limit.

## Acceptance criteria

- Scanning a DVD/Blu-ray UPC that UPCitemdb recognizes returns a populated `LookupResult`
  (`source="TMDB"`, title/cover/releaseYear/description set) instead of `NOT_FOUND`.
- A second scan of that same barcode (any user, any collection) resolves from the cache with no
  outbound call to UPCitemdb or TMDB.
- A barcode that fails every source gets cached as a negative entry; a second scan of it also
  produces no outbound calls and still correctly returns `NOT_FOUND`.
- Existing PRINT/AUDIO lookup behavior is unchanged on a cache miss (same sources, same order,
  same results) — the cache is purely an added first-check layer, not a behavior change to what
  each category resolves to.
- UPCitemdb calls are throttled to its documented limit using the same `AtomicLong`-based
  `throttle()` pattern `MusicBrainzService` already uses, not a fixed per-call delay.
- Backend tests cover: cache hit (positive), cache hit (negative), cache miss → full chain →
  write-through, and the extended non-ISBN chain reaching UPCitemdb→TMDB only after Discogs and
  MusicBrainz both fail.

## Open questions

- None blocking — the one real design fork (category-aware routing vs. extending the try-chain)
  is resolved above in favor of extending the chain, consistent with existing conventions.
