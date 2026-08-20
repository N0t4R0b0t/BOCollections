# Stretch Goals & Planned Features

This document captures the bigger features planned for BOCollections beyond the basic CRUD and scanner. Ordered roughly by implementation priority.

---

## 1. Mobile-first responsive layout

**Current state:** The sidebar layout works on a desktop browser. On a phone it collapses poorly.

**Plan:**

Replace the persistent sidebar with a bottom tab bar on narrow viewports:

```
Desktop (≥ 768px)           Mobile (< 768px)
┌──────┬────────────────┐   ┌────────────────────┐
│ Side │                │   │   Content area     │
│ bar  │  Content area  │   │   (full width)     │
│      │                │   ├────────────────────┤
└──────┴────────────────┘   │ 📚  🔍  📷  👤    │
                             └────────────────────┘
                                bottom tab bar
```

Implementation approach:
- `AppLayout` reads a `useMediaQuery('(max-width: 767px)')` hook.
- On mobile, render a `<nav>` fixed to the bottom with icon-only tabs.
- Scanner and GuidedCapture are already full-width and camera-centred — they need minimal changes.
- Collection grid moves from `sm:grid-cols-2` to a single-column list on mobile.
- Item cards get a more compact layout (horizontal thumbnail + text, not vertical).

Key files: `AppLayout.tsx`, `CollectionsPage.tsx`, `CollectionDetailPage.tsx`.

---

## 2. Thrifting mode (mobile — killer feature)

> **Status: substantially implemented**, not a "continuous video frames" feed (that turned out
> not to work — see `docs/specs/thrifting-mode-revamp.md`) but a presence-gated single-shot
> capture per shelf section, with the 4-tier taste-profile scoring, session/sighting history,
> and native camera parity this doc originally called for. What remains from the original
> vision below: none of it materially — this section is kept for historical context.

A user at a thrift store, record fair, or flea market holds their phone up at a shelf of CDs, book spines, or DVD cases. The app:

1. Takes a photo (or continuous video frames).
2. Sends it to the AI (Ollama vision, Gemini if available).
3. Gets back a list of identified items with bounding-box positions.
4. Overlays coloured annotations directly on the photo:
   - **Green outline** → you don't own this, and it looks interesting based on your taste.
   - **Blue outline** → you already own this exact item.
   - **Yellow outline** → you own a different version/edition.
   - **Orange outline** → low priority / outside your taste profile.
5. Tapping an annotation → item mini-card with quick-add button.

**AI prompt strategy:**

```
You are helping a collector at a thrift store.
Given this image of a shelf, identify every visible item (CD, book, DVD, etc.)
Return JSON:
{
  "items": [
    {
      "title": "...",
      "artist_or_author": "...",
      "format": "CD|Vinyl|Book|DVD|...",
      "bbox": {"x": 0.12, "y": 0.05, "w": 0.08, "h": 0.90},  // relative coords
      "confidence": "HIGH|MEDIUM|LOW"
    }
  ]
}
```

Backend then cross-references each identified title against the user's collection and taste profile.

**Taste profile** (to implement):
- Derived from existing collection: genres, artists, publishers, decades.
- Stored as a JSONB column on the `User` entity, refreshed asynchronously (RabbitMQ job) whenever the collection changes.

**Cost management:**
- Use Ollama (llava-phi3 or moondream) for continuous frames — free, local.
- Escalate to Gemini only for ambiguous high-value items (user-triggered, not automatic).

**New backend endpoints:**
```
POST /thrift/scan          { imageBase64, collectionIds[] }
                           → { items: [{ title, bbox, ownedStatus, suggestion }] }
```

**New frontend:**
- `ThriftingPage` — camera full-screen, tap to capture, overlay rendered on canvas.
- `ThriftResultOverlay` — SVG/canvas annotation layer on top of the photo.
- `ThriftItemCard` — bottom sheet that appears when tapping an annotation.

---

## 3. Shelf audit mode

The user photographs their own shelf at home to check what they have vs what the app thinks they have.

Flow:
1. Take a wide shot of a shelf section.
2. AI identifies all spines visible.
3. Cross-reference against the target collection.
4. Report:
   - Items the app knows about that **are** visible → confirmed ✓
   - Items in the collection that **are not** visible → possibly missing / lent out ⚠️
   - Items visible but **not in** the collection → should be added?

This shares most logic with thrifting mode (same image-to-items pipeline), just with a different collection comparison direction.

---

## 4. Buying assistant

When an item is found (via scanner or thrifting mode), surface useful buying context:

| Signal | Source | Notes |
|---|---|---|
| "You already own this" | own catalogue | Always shown |
| "Different edition — you have the CD, this is the vinyl" | own catalogue | Duplicate detection |
| "This would complete your Beatles discography" | collection analysis | Based on owned items by same artist |
| "Fair price" / "Below market" | Discogs price guide | `GET /marketplace/price_suggestions/{release_id}` |
| "eBay listings" | eBay Browse API | Requires eBay dev account |

The Discogs price guide is the most actionable — it returns min/median/max for a release in a given condition. This can be shown directly in the `ScanResultCard`.

**New field on `LookupResult`:**
```java
private PriceGuide priceGuide;  // { median, min, max, currency, condition }
```

Populate it in `DiscogsService` when a release is found (Discogs returns price suggestions on the release detail endpoint).

---

## 5. Electron app

Wrap the Vite build in an Electron shell for an installable desktop app that:
- Works fully offline (Ollama is already local).
- Has camera access without browser permissions prompts.
- Can run as a dedicated scanning station.

**Approach:**
- Add `electron` and `electron-builder` to the frontend devDependencies.
- `electron/main.ts` — creates a `BrowserWindow` pointed at `dist/index.html` (or `localhost:5173` in dev).
- Backend stays as a Spring Boot jar; Electron's main process spawns it on startup and kills it on quit.
- `electron-builder` produces `.AppImage` (Linux), `.dmg` (macOS), `.exe` (Windows).

The backend URL becomes `http://localhost:8080` regardless of environment, so no CORS changes needed.

**Key advantage over pure browser:** Electron has access to `nativeImage` and the OS camera APIs without the `getUserMedia` permission dance. The `BarcodeDetector` API is available in Chromium (Electron's engine) so the scanner works identically.

---

## 6. Bulk import from CSV / Libib export

Libib can export a collection as CSV. A one-shot import endpoint would let users migrate without re-scanning everything:

```
POST /import/libib-csv    multipart/form-data, file=export.csv
```

The CSV has columns: `Title`, `Creator`, `Format`, `ISBN/UPC`, `Notes`, etc. The importer:
1. Parses each row.
2. Looks up barcode → external API to enrich with cover art, publisher, year.
3. Creates `Item` + `CollectionEntry` records.
4. Returns a summary: `{ imported: 142, skipped: 3, errors: [] }`.

---

## 7. Collection sharing & public profiles

Let users make a collection (or their whole library) publicly viewable via a shareable link:

```
GET /public/{username}/{collection-slug}
```

No auth required. Read-only. Shows cover grid, item count, category breakdown.

Requires:
- `Collection.visibility` enum: `PRIVATE | LINK_SHARED | PUBLIC`
- A slug field on `Collection` (URL-safe name, unique per user).
- A public-facing controller with no auth filter.

---

## Implementation order suggestion

1. **Mobile layout** — unblocks phone use without any new backend work.
2. **Thrifting mode** — the flagship differentiator; shares the vision pipeline already built.
3. **Buying assistant (Discogs price guide)** — low effort, high value, already have Discogs wired.
4. **Shelf audit** — reuses thrifting pipeline, different UX.
5. **Electron** — straightforward wrapper once the web app is stable.
6. **Bulk import** — useful for onboarding, not urgent.
7. **Public profiles** — social feature, last priority.
