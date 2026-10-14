-- Offline capture recovery (docs/capture-ingestion.md, "Offline capture and the local upload queue"). A capture client
-- that lost its connection retries requests whose answers it never saw. Each retry must find what the first attempt
-- created instead of creating it again:
--   * capture_session.client_session_id: the id the client gave the session when it was recorded locally. Creating a
--     session again with the same id, by the same operator, returns the existing session.
--   * capture_media.client_file_id: the id the client gave the file in its local queue. Opening an upload again with the
--     same id in the same capture returns the existing upload (and its verdict), so a file is never published twice.
-- Both are optional: clients that do not send them keep the old behaviour.

ALTER TABLE capture_session ADD COLUMN client_session_id uuid;
CREATE UNIQUE INDEX capture_session_client_session_idx
    ON capture_session (organization_id, operator_id, client_session_id) WHERE client_session_id IS NOT NULL;

ALTER TABLE capture_media ADD COLUMN client_file_id uuid;
CREATE UNIQUE INDEX capture_media_client_file_idx
    ON capture_media (capture_session_id, client_file_id) WHERE client_file_id IS NOT NULL;

-- An erased capture must stay erased: a client still holding the session's files offline would otherwise recreate it
-- on its next synchronisation, under the same clientSessionId. Erasure (dev.chaya.api.erasure.ErasureService) records
-- the erased session's client id here, and creating a session with it is refused (410 CAPTURE_ERASED). Only the
-- operator subject and the client's random id are kept: nothing about the capture, its venue or its content.
CREATE TABLE capture_client_session_tombstone (
    organization_id   uuid NOT NULL,
    operator_id       text NOT NULL,
    client_session_id uuid NOT NULL,
    erased_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, operator_id, client_session_id)
);
