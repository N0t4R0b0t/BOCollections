-- A permanent photo gallery on catalogue items, mirroring scan_draft_photos. Draft photos are
-- currently discarded once a draft is approved (only draft.cover_url survives onto the item) —
-- this lets a user reopen an already-saved item, add more shots, or re-run AI vision against
-- them later, with the evidence actually kept around instead of starting from zero every time.
CREATE TABLE IF NOT EXISTS item_photos (
    id          BIGSERIAL PRIMARY KEY,
    item_id     BIGINT      NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    storage_key VARCHAR(255) NOT NULL,
    angle       VARCHAR(20) NOT NULL, -- REFERENCE, FRONT, BACK, SPINE, DISC
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_item_photos_item_id ON item_photos (item_id);
