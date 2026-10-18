package dev.chaya.api.stitch;

import dev.chaya.api.pipeline.PipelineDtos.RunView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire records of rooms and multi-room stitching (docs/multi-room.md). */
public final class StitchDtos {

    private StitchDtos() {}

    /** kind: ROOM, CORRIDOR, HALL or OTHER. */
    public record CreateRoom(@NotBlank @Size(max = 120) String name, String kind) {}

    /** A room's latest version: what a stitch would use by default when it is FINALIZED. awaitingCalibration: its run
     * succeeded but its reconstruction has no canonical frame yet (calibrate it, then finalize-current). */
    public record RoomVersion(UUID id, int versionNumber, String status, UUID pipelineRunId, String runStatus,
                              UUID coordinateFrameId, Instant finalizedAt, Instant createdAt, boolean awaitingCalibration) {}

    /** latestCapture: the room's most recent capture (its id and status), to follow its processing. */
    public record RoomView(UUID id, UUID floorId, String name, String kind, Instant createdAt, RoomVersion latestVersion,
                           RoomVersion latestFinalizedVersion, UUID latestCaptureId, String latestCaptureStatus) {}

    /** One room of a stitch request: the room, and the FINALIZED room version to use (default: its latest). */
    public record StitchRoomRequest(@NotNull UUID roomId, UUID scanVersionId) {}

    /**
     * anchorRoomId: the room whose reconstruction frame the stitched floor is written in (default: the first).
     * allowUnconfirmedRooms: accept rooms that only one chain of registrations places (no loop through them confirms it,
     * so a wrong registration on that chain could not be detected: repeated structure passes every pairwise gate). Off by
     * default, when such rooms are refused; a floor without loops (two rooms, rooms that each open onto one corridor) is
     * only stitched with it on. Recorded with the stitch.
     */
    public record StitchRequest(@NotNull @Size(min = 2) List<@Valid @NotNull StitchRoomRequest> rooms, UUID anchorRoomId,
                                Integer timeBudgetSeconds, Boolean allowUnconfirmedRooms) {}

    /**
     * One room of a stitch, as registered: registered is null until ROOM_REGISTRATION has reported; then true, or false
     * with the reason (no consistent registration links it to the anchor, what contradicted it, or that no loop confirms
     * it). loopConfirmed: for a registered room, whether two independent chains of registrations place it; false for one
     * placed only because the stitch accepted unconfirmed rooms.
     */
    public record StitchRoom(UUID roomId, String name, UUID roomVersionId, int roomVersionNumber, UUID coordinateFrameId,
                             boolean anchor, Boolean registered, String reason, Boolean loopConfirmed) {}

    /** A pair of rooms ROOM_REGISTRATION tried: outcome REGISTERED, NO_SHARED_FEATURES, FREE_SPACE_CONFLICT,
     * SCALE_INCONSISTENT, AMBIGUOUS, WEAK_OVERLAP, TILTED or RESIDUAL_TOO_HIGH (chaya_worker.room_registration.OUTCOMES),
     * with the gates it failed; edgeStatus what loop closure made of an accepted one (ACCEPTED, INCONSISTENT, AMBIGUOUS). */
    public record StitchPair(UUID sourceRoomId, UUID targetRoomId, String status, String outcome, List<String> failedGates,
                             String edgeStatus) {}

    /**
     * A stitch: its DRAFT/FINALIZED floor version (id), the run and capture that process it (follow its progress with the
     * capture's processing endpoints and stream), its rooms and how each was registered, the pairs tried, and what the
     * stitch and the navmesh measured (registration, stitch, connectivity: as recorded on the version, null until the
     * stage reported). current: the floor publishes it.
     */
    public record StitchView(UUID id, UUID floorId, int versionNumber, String status, boolean current, UUID captureId, UUID scanId,
                             RunView run, UUID anchorRoomId, boolean allowUnconfirmedRooms, List<StitchRoom> rooms, List<StitchPair> pairs,
                             Map<String, Object> registration, Map<String, Object> stitch, Map<String, Object> connectivity,
                             Instant createdAt, Instant finalizedAt, String createdBy) {}
}
