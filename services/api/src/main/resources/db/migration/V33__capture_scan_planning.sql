-- Automatic capture planning (docs/capture-hud.md, docs/route-planning.md section 12).
--
-- What a reconnaissance walk reports, what the room bounds were estimated from, and every scan plan made from them, kept
-- with the capture session so processing and audit tools can tell PREDICTED coverage (what a plan expected its route to
-- add, by the planner's model) apart from OBSERVED coverage (the same model applied to positions actually recorded).
--
-- 1. capture_hud_pose_sample gains where each position came from in a way that can be checked: source is one of the
--    supported position sources, and frame_id names the tracking frame it is expressed in (an AR session's world frame
--    exists only for that session). Video is not a position source (docs/capture-hud.md section 2).
-- 2. capture_hud_surface_sample: surface points the device's tracker reported (WebXR hit tests against detected planes),
--    with the camera position they were observed from. They are the only evidence walls are estimated from.
-- 3. capture_hud_tracking_event: tracking state changes and session problems the client observed (lost tracking, camera
--    permission denied, unsupported device, interrupted session, ...). Positions are never joined across a loss.
-- 4. capture_hud_scene records whether the bounds were MEASURED by the operator or ESTIMATED from evidence, in which frame,
--    and the full estimate report (confidence, unobserved boundary, assumptions).
-- 5. capture_plan: every scan plan made for the capture, versioned and append-only (a new plan supersedes the previous one).

ALTER TABLE capture_hud_pose_sample
    ADD COLUMN frame_id        text CHECK (frame_id IS NULL OR length(frame_id) BETWEEN 1 AND 100),
    ADD COLUMN frame_alignment text NOT NULL DEFAULT 'NATIVE' CHECK (frame_alignment IN ('NATIVE', 'OPERATOR_ALIGNED')),
    -- NOT VALID: rows written before this migration were never checked; every new row is.
    ADD CONSTRAINT capture_hud_pose_sample_source_supported
        CHECK (source IN ('manual', 'webxr', 'arkit', 'operator-trajectory')) NOT VALID;

CREATE TABLE capture_hud_surface_sample (
    id                 bigserial PRIMARY KEY,
    capture_session_id uuid NOT NULL,
    venue_id           uuid NOT NULL,
    organization_id    uuid NOT NULL,
    captured_at_ms     bigint NOT NULL,
    origin_x           double precision NOT NULL CHECK (origin_x = origin_x),
    origin_y           double precision NOT NULL CHECK (origin_y = origin_y),
    hit_x              double precision NOT NULL CHECK (hit_x = hit_x),
    hit_y              double precision NOT NULL CHECK (hit_y = hit_y),
    kind               text NOT NULL CHECK (kind IN ('WALL', 'FLOOR')),
    normal_degrees     double precision CHECK (normal_degrees IS NULL OR normal_degrees = normal_degrees),
    source             text NOT NULL CHECK (source IN ('webxr', 'arkit', 'operator-trajectory')),
    frame_id           text CHECK (frame_id IS NULL OR length(frame_id) BETWEEN 1 AND 100),
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

CREATE INDEX capture_hud_surface_sample_session_idx ON capture_hud_surface_sample (capture_session_id, captured_at_ms);

CREATE TABLE capture_hud_tracking_event (
    id                 bigserial PRIMARY KEY,
    capture_session_id uuid NOT NULL,
    venue_id           uuid NOT NULL,
    organization_id    uuid NOT NULL,
    captured_at_ms     bigint NOT NULL,
    state              text NOT NULL CHECK (state IN ('SESSION_STARTED', 'TRACKING', 'LIMITED', 'LOST', 'INTERRUPTED', 'RESUMED',
                                                      'SESSION_ENDED', 'PERMISSION_DENIED', 'UNSUPPORTED', 'ORIENTATION_CHANGED',
                                                      'REALIGNED')),
    source             text NOT NULL CHECK (source IN ('manual', 'webxr', 'arkit', 'camera', 'operator-trajectory')),
    frame_id           text CHECK (frame_id IS NULL OR length(frame_id) BETWEEN 1 AND 100),
    detail             text CHECK (detail IS NULL OR length(detail) <= 500),
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

CREATE INDEX capture_hud_tracking_event_session_idx ON capture_hud_tracking_event (capture_session_id, captured_at_ms);

ALTER TABLE capture_hud_scene
    ADD COLUMN scene_source        text NOT NULL DEFAULT 'MEASURED' CHECK (scene_source IN ('MEASURED', 'ESTIMATED')),
    ADD COLUMN frame_id            text CHECK (frame_id IS NULL OR length(frame_id) BETWEEN 1 AND 100),
    ADD COLUMN bounds_estimate_json jsonb CHECK (bounds_estimate_json IS NULL OR jsonb_typeof(bounds_estimate_json) = 'object'),
    ADD CONSTRAINT capture_hud_scene_estimate_present CHECK (scene_source <> 'ESTIMATED' OR bounds_estimate_json IS NOT NULL);

CREATE TABLE capture_plan (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       uuid NOT NULL,
    venue_id              uuid NOT NULL,
    capture_session_id    uuid NOT NULL,
    version               integer NOT NULL CHECK (version > 0),
    scene_source          text NOT NULL CHECK (scene_source IN ('MEASURED', 'ESTIMATED')),
    frame_id              text,
    scene_json            jsonb NOT NULL CHECK (jsonb_typeof(scene_json) = 'object'),
    bounds_estimate_json  jsonb CHECK (bounds_estimate_json IS NULL OR jsonb_typeof(bounds_estimate_json) = 'object'),
    recon_json            jsonb NOT NULL CHECK (jsonb_typeof(recon_json) = 'object'),
    priority_regions_json jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(priority_regions_json) = 'array'),
    config_json           jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(config_json) = 'object'),
    -- Which stored evidence the plan was computed from: counts per source, id high-water marks, time window.
    evidence_json         jsonb NOT NULL CHECK (jsonb_typeof(evidence_json) = 'object'),
    -- PREDICTED: the planner's PlanResult (route, waypoints, predicted coverage of lap + route).
    planned_json          jsonb NOT NULL CHECK (jsonb_typeof(planned_json) = 'object'),
    -- OBSERVED at planning time: coverage of the positions recorded so far, by the same model.
    observed_at_plan_json jsonb NOT NULL CHECK (jsonb_typeof(observed_at_plan_json) = 'object'),
    -- Positions captured after this instant (epoch ms, client clock) count toward the plan's progress.
    planned_after_ms      bigint NOT NULL,
    created_by            text NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    superseded_at         timestamptz,
    UNIQUE (capture_session_id, version),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

-- At most one current (not superseded) plan per capture.
CREATE UNIQUE INDEX capture_plan_current_idx ON capture_plan (capture_session_id) WHERE superseded_at IS NULL;
