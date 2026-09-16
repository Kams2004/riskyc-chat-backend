import { Room as ProtooRoom } from 'protoo-server';
import type { Router, WebRtcServer } from 'mediasoup/types';

import { GroupCallPeer } from './peer.js';

/**
 * One active group call. roomId === groupId (a group's id already doubles as
 * its conversationId elsewhere in this codebase — reusing it as the SFU room
 * id too keeps "one active call per group" implicit rather than needing
 * separate call-session bookkeeping). Fully in-memory/ephemeral, same MVP
 * tradeoff already established for media-service/presence-service (no
 * Postgres here).
 */
export class GroupCallRoom {
  readonly groupId: string;
  readonly router: Router;
  /** The WebRtcServer of whichever worker this room's router was created on (see roomManager's round-robin assignment) — every transport in this room must be created against this same one. */
  readonly webRtcServer: WebRtcServer;
  readonly protooRoom = new ProtooRoom();
  readonly peers = new Map<string, GroupCallPeer>();
  readonly startedAt = new Date();
  readonly callType: 'AUDIO' | 'VIDEO';
  /** Everyone who was ever in this room, not just current peers — fed to the /internal/group-calls/ended summary once the room closes. */
  readonly everParticipantIds = new Set<string>();

  constructor(groupId: string, router: Router, webRtcServer: WebRtcServer, callType: 'AUDIO' | 'VIDEO') {
    this.groupId = groupId;
    this.router = router;
    this.webRtcServer = webRtcServer;
    this.callType = callType;
  }

  addPeer(peer: GroupCallPeer): void {
    this.peers.set(peer.id, peer);
    this.everParticipantIds.add(peer.userId);
  }

  removePeer(peerId: string): void {
    this.peers.get(peerId)?.closeMedia();
    this.peers.delete(peerId);
  }

  get isEmpty(): boolean {
    return this.peers.size === 0;
  }

  /** Broadcasts a protoo notification to every peer except the given one (or all, if omitted). */
  notifyOthers(method: string, data: Record<string, unknown>, excludePeerId?: string): void {
    for (const peer of this.peers.values()) {
      if (peer.id === excludePeerId) continue;
      peer.protooPeer.notify(method, data).catch(() => {
        // Best-effort — a peer whose socket just dropped will fail here
        // harmlessly; its own 'close' handler (see protooServer.ts) is what
        // actually cleans it up.
      });
    }
  }

  close(): void {
    for (const peer of this.peers.values()) {
      peer.closeMedia();
    }
    this.protooRoom.close();
    this.router.close();
  }
}
