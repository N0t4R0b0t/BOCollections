-- A real photo gallery per sighting, replacing the single photo_storage_key column (which forced
-- "first photo wins, delete any later duplicate"). The in-store capture flow still stores exactly
-- one photo per scan/classify call — this just lets that storage accumulate over repeat sightings
-- and later at-home review/re-extraction, instead of discarding everything after the first shot.
CREATE TABLE IF NOT EXISTS thrift_sighting_photos (
    id          BIGSERIAL PRIMARY KEY,
    sighting_id BIGINT      NOT NULL REFERENCES thrift_sightings (id) ON DELETE CASCADE,
    storage_key VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_thrift_sighting_photos_sighting_id ON thrift_sighting_photos (sighting_id);

INSERT INTO thrift_sighting_photos (sighting_id, storage_key)
SELECT id, photo_storage_key FROM thrift_sightings WHERE photo_storage_key IS NOT NULL;

ALTER TABLE thrift_sightings DROP COLUMN photo_storage_key;
