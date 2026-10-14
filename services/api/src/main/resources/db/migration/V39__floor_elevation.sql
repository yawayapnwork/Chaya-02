-- Relative floor elevation (docs/navigation.md, "Floors"). Each floor is reconstructed and calibrated on its own, so the
-- height of one floor above another is not something Chaya measures: it is recorded by venue staff, from a survey or a
-- measurement on site, with who recorded it and how. Unknown stays NULL; nothing is ever estimated or defaulted.
--
--   elevation_m          height of the floor's finished floor level above the venue's elevation reference, metres
--   elevation_source     SURVEYED (from a survey or drawings of record) or MEASURED_ON_SITE (tape, laser, levelling)
--   elevation_reference  what 0 m is ("ground floor finished floor level", "survey datum"), free text
--
-- All four elevation columns are set together or not at all.

ALTER TABLE floor
    ADD COLUMN elevation_m double precision CHECK (elevation_m IS NULL OR (elevation_m > -1000 AND elevation_m < 1000)),
    ADD COLUMN elevation_source text CHECK (elevation_source IS NULL OR elevation_source IN ('SURVEYED', 'MEASURED_ON_SITE')),
    ADD COLUMN elevation_reference text,
    ADD COLUMN elevation_recorded_by text,
    ADD COLUMN elevation_recorded_at timestamptz,
    ADD CONSTRAINT floor_elevation_complete CHECK (
        (elevation_m IS NULL AND elevation_source IS NULL AND elevation_recorded_by IS NULL AND elevation_recorded_at IS NULL)
        OR (elevation_m IS NOT NULL AND elevation_source IS NOT NULL AND elevation_recorded_by IS NOT NULL
            AND elevation_recorded_at IS NOT NULL));

-- A deleted floor (soft-deleted, kept for its history) no longer holds its level: another floor may take it.
ALTER TABLE floor DROP CONSTRAINT floor_venue_id_level_key;
CREATE UNIQUE INDEX floor_venue_level_live_idx ON floor (venue_id, level) WHERE deleted_at IS NULL;
