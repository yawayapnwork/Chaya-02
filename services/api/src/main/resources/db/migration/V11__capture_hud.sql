-- Live capture-quality and coverage HUD: the operator's room outline for a capture, the pose samples reported
-- while capturing, and the frame-quality samples computed in the browser. All three are durable (REST, not just
-- in-memory) so the HUD survives a server restart or a reconnect; the live view (SSE) is computed from them.
--
-- No table stores a coverage percentage or a heatmap directly: those are always recomputed from these rows
-- through the same deterministic CapturePathPlanner used by route-plan (see docs/capture-hud.md).

-- One room-outline (plus optional planner config overrides) per capture, replaced wholesale when the operator
-- redraws it. Kept separate from capture_session so redrawing never touches session lifecycle columns.
CREATE TABLE capture_hud_scene (
    capture_session_id uuid PRIMARY KEY,
    venue_id            uuid NOT NULL,
    organization_id      uuid NOT NULL,
    scene_json          jsonb NOT NULL CHECK (jsonb_typeof(scene_json) = 'object'),
    config_json         jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(config_json) = 'object'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

CREATE TRIGGER capture_hud_scene_updated BEFORE UPDATE ON capture_hud_scene
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One row per reported device pose (position, optional heading). Append-only: a capture's path is the full
-- history, never edited in place. `source` records where the position came from (currently "manual": the
-- operator marking their spot on the floor plan; future on-device SLAM-lite sources plug in alongside it).
CREATE TABLE capture_hud_pose_sample (
    id                  bigserial PRIMARY KEY,
    capture_session_id  uuid NOT NULL,
    venue_id            uuid NOT NULL,
    organization_id      uuid NOT NULL,
    captured_at_ms      bigint NOT NULL,
    x                   double precision NOT NULL CHECK (x = x), -- rejects NaN; +/-Infinity caught by application code
    y                   double precision NOT NULL CHECK (y = y),
    yaw_degrees         double precision CHECK (yaw_degrees IS NULL OR yaw_degrees = yaw_degrees),
    source              text NOT NULL DEFAULT 'manual',
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

CREATE INDEX capture_hud_pose_sample_session_idx ON capture_hud_pose_sample (capture_session_id, captured_at_ms);

-- One row per summarised frame-quality sample (never raw image bytes: blur/exposure/motion/feature signals are
-- computed in the browser from the live camera frame and only the numbers are sent).
CREATE TABLE capture_hud_quality_sample (
    id                          bigserial PRIMARY KEY,
    capture_session_id          uuid NOT NULL,
    venue_id                    uuid NOT NULL,
    organization_id              uuid NOT NULL,
    captured_at_ms              bigint NOT NULL,
    blur_score                  double precision NOT NULL,
    brightness_mean             double precision NOT NULL CHECK (brightness_mean BETWEEN 0 AND 255),
    shadow_clip_fraction        double precision NOT NULL CHECK (shadow_clip_fraction BETWEEN 0 AND 1),
    highlight_clip_fraction     double precision NOT NULL CHECK (highlight_clip_fraction BETWEEN 0 AND 1),
    motion_score                double precision,
    duplicate_frame             boolean NOT NULL DEFAULT false,
    feature_count               integer NOT NULL CHECK (feature_count >= 0),
    spacing_meters              double precision,
    warnings                    text[] NOT NULL DEFAULT '{}',
    created_at                  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

CREATE INDEX capture_hud_quality_sample_session_idx ON capture_hud_quality_sample (capture_session_id, captured_at_ms);
