-- Gallery order was previously implicit (insertion/id order) with no way for a user to change
-- it. Backfilled from each table's existing id order so nothing visibly reshuffles on upgrade.
ALTER TABLE item_photos ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_draft_photos ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY id) - 1 AS rn FROM item_photos
)
UPDATE item_photos SET sort_order = ranked.rn FROM ranked WHERE item_photos.id = ranked.id;

WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY draft_id ORDER BY id) - 1 AS rn FROM scan_draft_photos
)
UPDATE scan_draft_photos SET sort_order = ranked.rn FROM ranked WHERE scan_draft_photos.id = ranked.id;
