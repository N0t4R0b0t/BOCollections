-- approveDraft() previously hardcoded every item's externalSource to "MANUAL" regardless of
-- whether the draft was actually resolved via a barcode lookup against TMDB/OpenLibrary/Discogs/
-- MusicBrainz — that provenance existed transiently (LookupResult.source) but was never carried
-- onto the draft. This column lets it survive through to the created item.
ALTER TABLE scan_drafts ADD COLUMN external_source VARCHAR(50);
