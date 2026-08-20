-- Shelf-mode batch-analyze redesign: a match_score ranks results by collection relevance
-- (TasteProfileService.score), and per-photo bbox coordinates let the review UI show exactly
-- where in a given shelf photo a sighting was detected. bbox stays null for held-item-mode
-- photos (a single-item confirmation shot, not a shelf detection with a meaningful in-frame
-- position) and for photos added later during at-home review.
ALTER TABLE thrift_sightings ADD COLUMN match_score DOUBLE PRECISION;

ALTER TABLE thrift_sighting_photos ADD COLUMN bbox_x DOUBLE PRECISION;
ALTER TABLE thrift_sighting_photos ADD COLUMN bbox_y DOUBLE PRECISION;
ALTER TABLE thrift_sighting_photos ADD COLUMN bbox_w DOUBLE PRECISION;
ALTER TABLE thrift_sighting_photos ADD COLUMN bbox_h DOUBLE PRECISION;
