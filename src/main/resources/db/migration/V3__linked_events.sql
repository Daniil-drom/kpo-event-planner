-- Preserve internal IDs and personal plans when changing the external provider.
ALTER TABLE cities RENAME COLUMN kudago_slug TO slug;
ALTER TABLE cities ADD COLUMN source TEXT NOT NULL DEFAULT 'kudago';
ALTER TABLE cities ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE;
INSERT INTO cities(slug, name, timezone, source, enabled)
VALUES ('helsinki', 'Хельсинки', 'Europe/Helsinki', 'linked_events', TRUE);

ALTER TABLE places RENAME COLUMN kudago_id TO external_id;
ALTER TABLE places ALTER COLUMN external_id TYPE TEXT USING external_id::text;
ALTER TABLE places ADD COLUMN source TEXT NOT NULL DEFAULT 'kudago';
ALTER TABLE places DROP CONSTRAINT places_kudago_id_key;
ALTER TABLE places ADD CONSTRAINT places_source_external_key UNIQUE (source, external_id);

ALTER TABLE events RENAME COLUMN kudago_id TO external_id;
ALTER TABLE events ALTER COLUMN external_id TYPE TEXT USING external_id::text;
ALTER TABLE events ADD COLUMN source TEXT NOT NULL DEFAULT 'kudago';
ALTER TABLE events DROP CONSTRAINT events_kudago_id_key;
ALTER TABLE events ADD CONSTRAINT events_source_external_key UNIQUE (source, external_id);
ALTER TABLE events ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN source_status TEXT;
-- Unknown price must not be represented as a known paid/free offer.
ALTER TABLE events ALTER COLUMN is_free DROP NOT NULL;
ALTER TABLE events ALTER COLUMN is_free DROP DEFAULT;
ALTER TABLE categories ALTER COLUMN slug TYPE TEXT;
ALTER TABLE categories ALTER COLUMN name TYPE TEXT;
CREATE INDEX events_active_city_idx ON events(city_id) WHERE is_active;

-- Linked Events can provide a calendar date without a known time of day.
ALTER TABLE event_sessions ADD COLUMN has_start_time BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE event_sessions ADD COLUMN has_end_time BOOLEAN NOT NULL DEFAULT TRUE;
