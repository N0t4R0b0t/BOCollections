-- Taste profile cache (used to compute the "interesting" thrift-mode tier)
ALTER TABLE users ADD COLUMN taste_profile JSONB;
ALTER TABLE users ADD COLUMN taste_profile_updated_at TIMESTAMP;

-- Thrift sessions (one per shopping trip)
CREATE TABLE IF NOT EXISTS thrift_sessions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    location   VARCHAR(150),
    status     VARCHAR(10) NOT NULL DEFAULT 'OPEN', -- OPEN, CLOSED (reuses ScanSessionStatus values)
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_thrift_sessions_user_id ON thrift_sessions (user_id);

-- Distinct items sighted during a session (shelf-mode or held-item-mode), one row per
-- distinct normalized title per session — a repeat sighting bumps times_seen/last_seen_at
-- rather than inserting a duplicate row.
CREATE TABLE IF NOT EXISTS thrift_sightings (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT      NOT NULL REFERENCES thrift_sessions (id) ON DELETE CASCADE,
    user_id           BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title             VARCHAR(500) NOT NULL,
    normalized_title  VARCHAR(500) NOT NULL,
    category          VARCHAR(10),
    format            VARCHAR(50),
    artist_or_author  VARCHAR(255),
    publisher         VARCHAR(255),
    release_year      INT,
    owned_status      VARCHAR(20) NOT NULL, -- OWNED, DIFFERENT_VERSION, NOT_OWNED, INTERESTING
    item_id           BIGINT REFERENCES items (id) ON DELETE SET NULL,
    confidence        VARCHAR(10),
    photo_storage_key VARCHAR(255),
    source_mode       VARCHAR(10), -- SHELF, HELD_ITEM
    times_seen        INT         NOT NULL DEFAULT 1,
    last_seen_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_thrift_sightings_session_id ON thrift_sightings (session_id);
CREATE INDEX IF NOT EXISTS idx_thrift_sightings_user_id_normalized_title ON thrift_sightings (user_id, normalized_title);
