-- Enforces at the DB level what ThriftSessionService.recordSighting already assumes: at most
-- one sighting row per (session, normalized title). Without this, two near-simultaneous requests
-- for the same title in the same session (e.g. a shelf scan and a held-item classify racing) can
-- each pass the existence check before either commits, inserting a duplicate row instead of one
-- with times_seen bumped.
ALTER TABLE thrift_sightings
    ADD CONSTRAINT uq_thrift_sightings_session_normalized_title UNIQUE (session_id, normalized_title);
