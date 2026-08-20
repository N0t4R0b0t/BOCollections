-- Scan sessions (bulk scan mode — a scanning "trip" tied to one collection)
CREATE TABLE IF NOT EXISTS scan_sessions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    collection_id BIGINT      NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    status        VARCHAR(10) NOT NULL DEFAULT 'OPEN', -- OPEN, CLOSED
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scan_sessions_user_id ON scan_sessions (user_id);

-- Drafts collected during a session, pending review/approval into items + collection_entries
CREATE TABLE IF NOT EXISTS scan_drafts (
    id                    BIGSERIAL PRIMARY KEY,
    session_id            BIGINT      NOT NULL REFERENCES scan_sessions (id) ON DELETE CASCADE,
    status                VARCHAR(10) NOT NULL DEFAULT 'PENDING', -- PENDING, SKIPPED, APPROVED
    match_kind            VARCHAR(20) NOT NULL,                   -- CONFIDENT, VARIANT_MISMATCH, UNMATCHED, MANUAL
    existing_item_id      BIGINT      REFERENCES items (id) ON DELETE SET NULL,
    duplicate_of_draft_id BIGINT      REFERENCES scan_drafts (id) ON DELETE SET NULL,
    barcode               VARCHAR(64),
    category              VARCHAR(10),
    format                VARCHAR(50),
    title                 VARCHAR(500),
    subtitle              VARCHAR(500),
    description           TEXT,
    cover_url             VARCHAR(1000),
    release_year          INT,
    publisher             VARCHAR(255),
    metadata              JSONB,
    confidence            VARCHAR(10),
    created_at            TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scan_drafts_session_id ON scan_drafts (session_id);

-- User-captured photos for a draft (auto reference shot + optional guided-capture angles).
-- Online/generic cover art is NOT stored here — that's scan_drafts.cover_url, an external URL,
-- same as items.cover_url.
CREATE TABLE IF NOT EXISTS scan_draft_photos (
    id          BIGSERIAL PRIMARY KEY,
    draft_id    BIGINT      NOT NULL REFERENCES scan_drafts (id) ON DELETE CASCADE,
    storage_key VARCHAR(255) NOT NULL,
    angle       VARCHAR(20) NOT NULL, -- REFERENCE, FRONT, BACK, SPINE
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scan_draft_photos_draft_id ON scan_draft_photos (draft_id);
