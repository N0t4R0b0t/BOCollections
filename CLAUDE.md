# BOCollections

Smart collection management app — physical media (books, magazines, CDs, vinyl, DVDs, games, etc.).

## Stack

- **Backend**: Java 21 + Spring Boot 4 + PostgreSQL + Redis + RabbitMQ + Spring AI
- **Frontend**: React 19 + TypeScript + Vite + Tailwind CSS v4 + Zustand + React Router v7
- **AI**: Ollama (local) and Gemini (hosted) as vision endpoints, both selectable per-endpoint
  via `app.vision.endpoints` — see "Vision AI" below

## Package layout

- Backend package: `com.bocollections.backend`
- DB name: `collections`
- Context path: `/api`
- Flyway migrations: `backend/src/main/resources/db/migration/`

## Dev setup

```bash
# Infrastructure (Postgres :5433, RabbitMQ :5672/:15672, Redis :6380)
docker compose up -d

# Backend — starts on :8080 with spring.profiles.active=local
cd backend && ./gradlew bootRun

# Frontend — starts on :5173, proxies /api → :8080
cd frontend && npm run dev
```

> If port 6379 is already occupied (e.g. by another project's Redis), `application-local.yml`
> points to that instance directly. No changes needed.

> In a containerized dev workspace (Coder, devcontainer) where the backend runs outside the
> `docker compose` network, point `application-local.yml` at `host.docker.internal` instead
> of `localhost` for Postgres/Redis/RabbitMQ, and add any forwarded frontend port (e.g. the
> browser hits `:5174` instead of `:5173`) to `app.cors.allowed-origins`. See the sample in
> README.md's Configuration section.

> For testing the native Android app against this dev server from a phone, the VS Code task
> "Phone Proxy: Start" (part of "⚡ Start: Full Stack") brings up a `bocollections-phone-proxy`
> socat container relaying an external port into this workspace container's internal Docker IP
> — recreated (not just restarted) each time since that IP can change across workspace restarts.

## Known Spring AI quirks (2.0.0-M4)

- `UserMessage` uses a builder: `UserMessage.builder().text(prompt).media(media).build()`
  — there is no `UserMessage(String, List<Media>)` constructor.
- Ollama options class is `OllamaChatOptions` (not `OllamaOptions`).
- Google GenAI autoconfiguration crashes on startup when `GEMINI_API_KEY` is empty.
  Excluded in `application-local.yml` via `spring.autoconfigure.exclude`.
- Hibernate 7 auto-detects the PostgreSQL dialect — don't set `hibernate.dialect` explicitly
  or it logs a deprecation warning on every boot.
- `SecurityConfiguration.allowedOrigins` is bound with `@Value`, not `@ConfigurationProperties`
  — it only understands a single comma-separated string. Writing it as a YAML list (`- item`)
  doesn't error, it silently fails to resolve and falls back to the hardcoded default, which
  looks exactly like a CORS misconfiguration ("Invalid CORS request") with no clue it's a
  binding issue.
- **Spring's `JpaTransactionManager` cannot do `PROPAGATION_NESTED` (savepoint) transactions,
  full stop** — confirmed live against real Hibernate/Postgres, not just in theory. Neither the
  default `JpaDialect` nor `HibernateJpaDialect` implements `supportsSavepoints()`, so a nested
  `TransactionTemplate` throws `NestedTransactionNotSupportedException` unconditionally,
  regardless of how the transaction manager bean is configured. This only surfaces at runtime —
  a unit test that mocks `PlatformTransactionManager` never exercises real savepoint logic and
  will pass regardless. If you need "attempt this insert without poisoning the caller's
  transaction on a lost race," use `PROPAGATION_REQUIRES_NEW` (a fully independent transaction/
  connection) instead — same isolation goal, no savepoint required. See
  `ThriftSessionService.tryInsert()` for the working pattern.

## Media model

Items have a `category` (PRINT | AUDIO | VIDEO | GAME | OTHER) and a free-text `format`
(e.g. "Book", "Vinyl LP", "VHS", "Game Cartridge"). The `metadata` JSONB column stores
category-specific fields (authors, tracklist, runtime, platform, etc.).

## External metadata sources

| Category | Source | Auth |
|---|---|---|
| PRINT (ISBN-10/13) | Open Library | None |
| AUDIO (UPC) | Discogs | `DISCOGS_TOKEN` env var (optional) |
| AUDIO (UPC) | MusicBrainz | None — 1 req/sec rate limit enforced via `throttle()` |
| VIDEO (title, via UPCitemdb) | TMDB | `TMDB_API_KEY` env var (optional) |
| GAME (title, via UPCitemdb) | IGDB (Twitch OAuth) | `IGDB_CLIENT_ID`/`IGDB_CLIENT_SECRET` env vars (optional) |

Neither TMDB nor IGDB has barcode-native lookup — both are reached via a title `UpcItemDbService`
resolves from the barcode first, same shape as the VIDEO chain this comment used to describe alone.

Two more sources supply *only* extra cover-art candidates, layered into `metadata.physicalPhotos`
alongside UPCitemdb's own images rather than acting as a `LookupResult` source of their own:
- **eBay Browse API** (`EbayService`, `EBAY_CLIENT_ID`/`EBAY_CLIENT_SECRET`, optional) — real listing
  photos by GTIN, for VIDEO and GAME. eBay's Browse API requires a *production* keyset even for
  local dev/testing — its sandbox only serves synthetic listing data, so GTIN searches there never
  return anything real.
- **TheGamesDB** (`TheGamesDbService`, `THEGAMESDB_API_KEY`, optional) — GAME box art by title, the
  only one of these sources that separates front and back box art (its `side` field). Getting a key
  requires a manually-approved forum request, not instant self-serve like the others — expect
  `isConfigured()` to stay false until that's granted, same as any other optional token here.

`MetadataLookupService.promoteRealPhotoAsCover()` prefers a real physical-product photo (from
UPCitemdb/eBay/TheGamesDB) as `coverUrl` over TMDB's/IGDB's promotional poster/cover art when one
is available, folding the promotional art into `metadata.posterOptions` as an alternate instead of
discarding it.

Lookup order: own catalogue → (ISBN → OpenLibrary) or (UPC → Discogs → MusicBrainz → UPCitemdb+TMDB
→ UPCitemdb+IGDB), each barcode-resolved hit then enriched with eBay/TheGamesDB cover-art candidates.

## Vision AI

Default model: `llava-phi3` (change via `VISION_MODEL` env var or `app.vision.model` in yml).
Other options pulled: `llava:7b`, `moondream`.

`app.vision.endpoints` is a list of vision backends, tried in order (first success wins).
Each entry sets `provider: ollama` (default) or `provider: gemini`; Ollama entries use
`base-url`, Gemini entries use `api-key` (typically `${GEMINI_API_KEY}`). Mark one entry
`primary: true` to try it first regardless of list position — useful for running both
providers with Gemini (or Ollama) as the preferred one and the other as failover.
`VisualScanService` builds both `OllamaChatModel` and `GoogleGenAiChatModel` manually
(not via Spring AI autoconfiguration) — see `buildChatModel()`.

The `VisualScanService.extractJson()` helper strips prose that small vision models
wrap around JSON despite being asked for raw output. Don't remove it.

## Performance notes

- `CollectionService.getEntries` uses a batch `findAllById` to avoid N+1 queries —
  one query for the entries page + one query for all items on that page.
- `MusicBrainzService` uses an `AtomicLong` timestamp to sleep only the *remaining*
  interval, not a fixed delay on every call.

## Frontend conventions

- Shared Axios error helper: `src/utils/apiError.ts` — use in every catch block.
- The single-item `ScannerPage` described in earlier docs no longer exists. Bulk-scan mode
  (`ScanCapturePage` + `useCaptureLoop`) is the current capture flow: session → per-item draft
  → review → approve. Thrift mode (`ThriftCapturePage` + `useHeldItemLoop`/shelf-mode reducer)
  is a separate, simpler flow: session → sighting, no draft/approve step.
- Responsive layout: `< 768px` = mobile shell (MobileHeader + BottomTabBar),
  `≥ 768px` = sidebar. Controlled by `useMediaQuery('(max-width: 767px)')` in `AppLayout`.
- Never name a `useCallback` variable `fetch` — it shadows `window.fetch`.
- `h-18` is not a Tailwind class. Use `h-16`, `h-20`, or an arbitrary `h-[72px]`.

## Native camera architecture (Capacitor/Android)

The native ML-Kit barcode scanner (`@capacitor-mlkit/barcode-scanning`) and `getUserMedia` are
two independent camera clients — running both at once (or switching between them without a
clean handoff) causes real hardware contention (black screen, requires app restart). Hard-won
rules, all confirmed on a real device:

- Never run continuous `getUserMedia` on native while the ML-Kit scanner might also be active.
  Where both are genuinely needed (e.g. thrift held-item mode's barcode-confirm + OCR-capture),
  do a brief `pauseDetector()` → `startCamera()` → `captureFrame()` → `stopCamera()` →
  `resumeDetector()` sequence around a single capture, never both running continuously together.
  A ~400ms settle delay around the stop/resume boundary is needed for the OS to actually
  release the camera.
- `usePresenceDetector` needs a continuously live frame to do its luminance-diff analysis — it
  cannot be run on native at all without reintroducing the contention above. Native flows that
  need "something is in front of the camera" (like thrift's OCR fallback) use an explicit manual
  trigger button instead of automatic presence detection there; web keeps full automatic
  behavior since it has no competing native session.
- `CameraPreview`'s `fill` prop (skip the default `aspect-video`/`aspect-3/4` class) is required
  whenever the caller positions it via `absolute inset-0` — stacking both sizing strategies
  produces a visibly broken split/frozen render on a real device, not just a CSS quirk.
- `CameraPreview`'s `transparent` prop needs `pointer-events-none` on the container — Android's
  hardware-decoded video surface can swallow taps meant for DOM buttons on top of it, even after
  the CSS box has visually moved/shrunk on a relayout.
- Flexbox: a sibling of a `flex-1` container without `min-h-0` can get silently squeezed to ~0px
  by that container's implicit `min-height: auto` refusing to shrink. Give the growing side
  `min-h-0` and any panel that must never compress `shrink-0`.

## Photo galleries

Three parallel photo-gallery implementations — `ScanDraftPhoto`, `ItemPhoto`, `ThriftSightingPhoto`
— follow the same shape (`{id, storageKey/url, createdAt}`, add/delete endpoints, a read-only
`reextract` endpoint that re-runs AI vision and returns an `ExtractResponse` suggestion the
caller applies via a normal update, never auto-applied). Vision-read fields never overwrite a
barcode. The frontend's `AddPhotosPage` generalizes over all three (draft/item/sighting) via a
shared `Target` interface, reachable from `ScanReviewPage`, `ItemDetailPage`, and
`ThriftSessionsPage` respectively. `MediaController` treats item photos as shared catalogue data
(any authenticated user), scan-draft and thrift-sighting photos as user-owned (ownership-checked).

## Deployment (Proxmox VE LXC)

`ct/bocollections.sh` + `install/bocollections-install.sh` follow
[community-scripts/ProxmoxVED](https://github.com/N0t4R0b0t/ProxmoxVED) (this project's fork)
conventions — native apt install (Java/Postgres/Redis/RabbitMQ/Nginx), no Docker-in-LXC.
Release artifacts (backend jar + frontend bundle) are built by `.github/workflows/release.yml`
on `v*` tag push and published to Cloudflare R2 (bucket `bocollections`), not GitHub Releases.
See the README's "Proxmox VE LXC install" section for the install/update commands. The shared
ProxmoxVE framework (`misc/*.func`) is fetched from the fork above; `ct/`/`install/` scripts are
fetched from this repo — `ct/bocollections.sh` pre-seeds a throwaway local checkout so the
framework's own local-file-preference logic resolves each half from the right place.

## Stretch goals

See `STRETCH_GOALS.md` for the original feature roadmap. Thrifting mode (shelf mode + held-item
mode, taste-profile "interesting" tier, session/sighting history, native camera parity, photo
galleries) is substantially implemented — see `docs/specs/thrifting-mode-revamp.md` for the
design doc it was built against. Shelf audit, buying assistant, Electron, and bulk import remain
unstarted.
