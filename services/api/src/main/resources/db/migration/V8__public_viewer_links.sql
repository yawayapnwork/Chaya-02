-- Public (anonymous) viewer links and the short-lived tokens exchanged from them.
--
-- Only SHA-256 hashes of the secrets are stored; the raw link secret is shown once at creation.
-- A link is scoped to exactly one venue. Revocation (revoked_at) takes effect immediately because
-- every token use joins back to its link.

CREATE TABLE public_viewer_link (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    label           text,
    secret_hash     char(64) NOT NULL UNIQUE CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    created_by      text NOT NULL,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_by      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK (expires_at > created_at),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)),
    UNIQUE (id, venue_id)
);

CREATE TABLE public_viewer_token (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id     uuid NOT NULL REFERENCES public_viewer_link (id) ON DELETE RESTRICT,
    token_hash  char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);

CREATE INDEX public_viewer_link_venue_idx ON public_viewer_link (venue_id, created_at DESC);
CREATE INDEX public_viewer_token_link_idx ON public_viewer_token (link_id);

-- Audit rows written on behalf of an anonymous link visitor.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_actor_type_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_actor_type_check
    CHECK (actor_type IN ('USER', 'SERVICE', 'PUBLIC_VIEWER'));
