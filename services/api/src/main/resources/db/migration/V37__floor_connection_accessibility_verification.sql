-- STEP_FREE routing crosses floors only over a connection whose accessibility someone has verified on site
-- (dev.chaya.api.navigation.RouteService). V22 lets staff register a connection's clear width and a ramp's slope, but a
-- registered number is a claim: nobody has confirmed that the lift car really takes a wheelchair, or that the door
-- width was measured rather than copied from a plan. A connection is now usable step-free only once a venue manager has
-- recorded who verified it and when. The registered measurements cannot be edited after creation (there is no update
-- path), so a verification always refers to the values it was given for. Existing connections start unverified, so
-- step-free routes between floors fail closed until each one is verified.

ALTER TABLE floor_connection
    ADD COLUMN accessibility_verified_at timestamptz,
    ADD COLUMN accessibility_verified_by text,
    ADD CONSTRAINT floor_connection_accessibility_verification_complete
        CHECK ((accessibility_verified_at IS NULL) = (accessibility_verified_by IS NULL));
