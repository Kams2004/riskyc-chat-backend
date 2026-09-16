import { createServer, type Server as HttpServer } from 'node:http';

import { WebSocketServer } from 'protoo-server';

import { InvalidTokenError, verifyToken } from '../auth/jwt.js';
import { isRevoked } from '../auth/revokedJtiPoller.js';
import { GroupCallPeer } from '../mediasoup/peer.js';
import { closeRoomIfEmpty, getOrCreateRoom } from '../mediasoup/roomManager.js';
import { notifyGroupCallEnded } from '../integrations/messagingClient.js';
import { handleRequest } from './requestHandlers.js';

/**
 * Auth + room-join handshake happens once, at WS connect time, via query
 * params on the handshake URL — same convention as
 * WebSocketAuthInterceptor.java's `?token=` (WS handshakes can't carry
 * custom headers). Unlike that interceptor, an invalid/missing/revoked
 * token here DOES reject the connection outright (403) rather than
 * degrading to anonymous — there's no meaningful "anonymous" mode for a
 * call, unlike a plain topic subscription.
 */
export function createProtooServer(port: number): HttpServer {
  const httpServer = createServer((req, res) => {
    if (req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('ok');
      return;
    }
    res.writeHead(404);
    res.end();
  });
  const wsServer = new WebSocketServer(httpServer);

  wsServer.on('connectionrequest', (info, accept, reject) => {
    void handleConnectionRequest(info.request.url ?? '', accept, reject);
  });

  httpServer.listen(port, () => {
    console.log(`[protooServer] listening on :${port}`);
  });

  return httpServer;
}

async function handleConnectionRequest(
  requestUrl: string,
  accept: () => unknown,
  reject: (code: number, reason: string) => void
): Promise<void> {
  const params = new URL(requestUrl, 'http://localhost').searchParams;
  const token = params.get('token');
  const groupId = params.get('groupId');
  const displayName = params.get('displayName') ?? 'Someone';
  const callType = params.get('callType') === 'VIDEO' ? 'VIDEO' : 'AUDIO';

  if (!token) {
    reject(401, 'Missing token');
    return;
  }
  if (!groupId) {
    reject(400, 'Missing groupId');
    return;
  }

  let userId: string;
  try {
    const claims = verifyToken(token);
    if (isRevoked(claims.jti)) {
      reject(401, 'Session has been signed out');
      return;
    }
    userId = claims.userId;
  } catch (e) {
    if (e instanceof InvalidTokenError) {
      reject(401, 'Invalid token');
      return;
    }
    reject(500, 'Auth check failed');
    return;
  }

  const transport = accept();
  const { room, created } = await getOrCreateRoom(groupId, callType);

  // A user re-joining (new tab/device, or a reconnect after a dropped
  // socket) replaces their previous peer rather than being rejected —
  // protoo.Room.createPeer() throws on a duplicate id, and there's no
  // "resume" semantics here (see plan notes: a dropped WS means a full
  // rejoin with fresh transports, never a resume of old server-side state).
  const previous = room.protooRoom.getPeer(userId);
  if (previous) {
    previous.close();
    room.removePeer(userId);
  }

  const protooPeer = room.protooRoom.createPeer(userId, transport);
  const peer = new GroupCallPeer(protooPeer, userId, displayName);
  room.addPeer(peer);

  room.notifyOthers('peerJoined', { peerId: peer.id, displayName }, peer.id);

  protooPeer.on('request', (request, acceptReq, rejectReq) => {
    void handleRequest(room, peer, request, acceptReq, rejectReq);
  });

  protooPeer.on('close', () => {
    room.notifyOthers('peerLeft', { peerId: peer.id }, peer.id);
    room.removePeer(peer.id);
    const closedRoom = closeRoomIfEmpty(groupId);
    if (closedRoom) {
      void notifyGroupCallEnded({
        groupId,
        callType: closedRoom.callType,
        startedAt: closedRoom.startedAt.toISOString(),
        endedAt: new Date().toISOString(),
        participantIds: [...closedRoom.everParticipantIds],
      });
    }
  });

  console.log(
    `[protooServer] ${userId} ${created ? 'created' : 'joined'} room ${groupId} (${room.peers.size} peer(s))`
  );
}
