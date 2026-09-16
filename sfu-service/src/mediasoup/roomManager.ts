import { mediaCodecs } from './mediasoupConfig.js';
import { GroupCallRoom } from './room.js';
import { nextWorker } from './workerPool.js';

const rooms = new Map<string, GroupCallRoom>();

/** Start-or-join semantics: no room yet for this groupId = this call IS starting one (callType is whatever the starter requested); a room already there = the caller just joins it, its existing callType wins. */
export async function getOrCreateRoom(
  groupId: string,
  requestedCallType: 'AUDIO' | 'VIDEO'
): Promise<{ room: GroupCallRoom; created: boolean }> {
  const existing = rooms.get(groupId);
  if (existing) {
    return { room: existing, created: false };
  }
  const { worker, webRtcServer } = nextWorker();
  const router = await worker.createRouter({ mediaCodecs });
  const room = new GroupCallRoom(groupId, router, webRtcServer, requestedCallType);
  rooms.set(groupId, room);
  return { room, created: true };
}

export function getRoom(groupId: string): GroupCallRoom | undefined {
  return rooms.get(groupId);
}

/** Call after removing a peer — closes and forgets the room once its last participant has left. */
export function closeRoomIfEmpty(groupId: string): GroupCallRoom | null {
  const room = rooms.get(groupId);
  if (!room || !room.isEmpty) return null;
  room.close();
  rooms.delete(groupId);
  return room;
}
