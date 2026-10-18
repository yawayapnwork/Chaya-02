"use client";

import { api } from "./capture-api";
import type { Room, Stitch } from "./stitch-view";

/** Rooms of a floor and multi-room stitching (dev.chaya.api.stitch.StitchController, docs/multi-room.md). A room is captured
 * with the ordinary capture flow (createCapture with its roomId); a stitch is followed, retried and cancelled through its
 * capture (`captureId`) with the ordinary processing endpoints. */

export const listRooms = (venueId: string, floorId: string) => api<Room[]>(`/venues/${venueId}/floors/${floorId}/rooms`);

export const createRoom = (venueId: string, floorId: string, body: { name: string; kind?: string }) =>
  api<Room>(`/venues/${venueId}/floors/${floorId}/rooms`, { method: "POST", body: JSON.stringify(body) });

/** Finalizes the room's latest reconstruction once it is calibrated (it is never published as the floor). */
export const finalizeRoom = (venueId: string, floorId: string, roomId: string) =>
  api<Room>(`/venues/${venueId}/floors/${floorId}/rooms/${roomId}/versions/finalize-current`, { method: "POST" });

export const listStitches = (venueId: string, floorId: string) => api<Stitch[]>(`/venues/${venueId}/floors/${floorId}/stitches`);

export const getStitch = (venueId: string, floorId: string, stitchId: string) =>
  api<Stitch>(`/venues/${venueId}/floors/${floorId}/stitches/${stitchId}`);

export const startStitch = (venueId: string, floorId: string, body: {
  rooms: { roomId: string; scanVersionId?: string }[]; anchorRoomId: string; timeBudgetSeconds?: number;
  /** Accept rooms only one chain of registrations places (no loop confirms them); refused otherwise. */
  allowUnconfirmedRooms?: boolean;
}) => api<Stitch>(`/venues/${venueId}/floors/${floorId}/stitches`, { method: "POST", body: JSON.stringify(body) });
