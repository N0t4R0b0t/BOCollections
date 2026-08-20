-- Users
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    display_name  VARCHAR(80),
    password_hash VARCHAR(255),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_email UNIQUE (email)
);

-- Items (global catalogue; shared across all users)
CREATE TABLE IF NOT EXISTS items (
    id              BIGSERIAL PRIMARY KEY,
    barcode         VARCHAR(64),
    barcode_type    VARCHAR(20),          -- ISBN13, ISBN10, UPC, EAN13, CATALOG_NUMBER
    category        VARCHAR(10) NOT NULL, -- PRINT, AUDIO, VIDEO, GAME, OTHER
    format          VARCHAR(50) NOT NULL, -- "Book", "Vinyl LP", "VHS", "Game Cartridge", …
    title           VARCHAR(500) NOT NULL,
    subtitle        VARCHAR(500),
    description     TEXT,
    cover_url       VARCHAR(1000),
    release_year    INT,
    publisher       VARCHAR(255),         -- label / studio / publisher
    external_id     VARCHAR(255),
    external_source VARCHAR(30),          -- OPEN_LIBRARY, DISCOGS, TMDB, IGDB, MANUAL
    metadata        JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_items_barcode            ON items (barcode);
CREATE INDEX IF NOT EXISTS idx_items_category           ON items (category);
CREATE INDEX IF NOT EXISTS idx_items_external_source_id ON items (external_source, external_id);
CREATE INDEX IF NOT EXISTS idx_items_title_gin          ON items USING gin (to_tsvector('english', title));

-- Collections (per-user, named groups of items)
CREATE TABLE IF NOT EXISTS collections (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(100) NOT NULL,
    description      TEXT,
    primary_category VARCHAR(10), -- nullable = mixed collection
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_collections_user_id ON collections (user_id);

-- Collection entries (item ↔ collection link with ownership metadata)
CREATE TABLE IF NOT EXISTS collection_entries (
    id               BIGSERIAL PRIMARY KEY,
    collection_id    BIGINT         NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    item_id          BIGINT         NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    user_id          BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    condition        VARCHAR(15)    NOT NULL DEFAULT 'UNKNOWN',
    notes            TEXT,
    acquisition_date DATE,
    purchase_price   NUMERIC(10, 2),
    location         VARCHAR(100),
    created_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_entries_collection_item UNIQUE (collection_id, item_id)
);

CREATE INDEX IF NOT EXISTS idx_entries_collection_id ON collection_entries (collection_id);
CREATE INDEX IF NOT EXISTS idx_entries_item_id       ON collection_entries (item_id);
CREATE INDEX IF NOT EXISTS idx_entries_user_id       ON collection_entries (user_id);
