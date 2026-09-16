import type { ProtooRequest } from 'protoo-server';
import type { MediaKind, RtpParameters } from 'mediasoup/types';

import { config } from '../config.js';
import type { GroupCallPeer } from '../mediasoup/peer.js';
import type { GroupCallRoom } from '../mediasoup/room.js';
import { notifyGroupCallInvite } from '../integrations/messagingClient.js';

const iceServers = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: config.turnServerUrl, username: config.turnUsername, credential: config.turnCredential },
];

type Accept = (data?: Record<string, unknown>) => void;
type Reject = (errorCode: number, errorReason?: string) => void;

/**
 * One handler per protoo request method. `room`/`peer` are already resolved
 * by the time this runs (see protooServer.ts's per-connection closure) — this
 * module is pure request-shape logic, no connection/auth concerns.
 */
export async function handleRequest(
  room: GroupCallRoom,
  peer: GroupCallPeer,
  request: ProtooRequest,
  accept: Accept,
  reject: Reject
): Promise<void> {
  try {
    switch (request.method) {
      case 'getRouterRtpCapabilities': {
        accept({ rtpCapabilities: room.router.rtpCapabilities });
        return;
      }

      case 'createWebRtcTransport': {
        const direction = request.data?.direction;
        if (direction !== 'send' && direction !== 'recv') {
          reject(400, 'direction must be "send" or "recv"');
          return;
        }
        const transport = await room.router.createWebRtcTransport({
          webRtcServer: room.webRtcServer,
          enableUdp: true,
          enableTcp: true,
          preferUdp: true,
        });
        if (direction === 'send') {
          peer.sendTransport = transport;
        } else {
          peer.recvTransport = transport;
        }
        transport.on('dtlsstatechange', (dtlsState) => {
          if (dtlsState === 'closed' || dtlsState === 'failed') {
            transport.close();
          }
        });
        accept({
          id: transport.id,
          iceParameters: transport.iceParameters,
          iceCandidates: transport.iceCandidates,
          dtlsParameters: transport.dtlsParameters,
          iceServers,
        });
        return;
      }

      case 'connectWebRtcTransport': {
        const { transportId, dtlsParameters } = request.data as {
          transportId: string;
          dtlsParameters: Parameters<NonNullable<typeof peer.sendTransport>['connect']>[0]['dtlsParameters'];
        };
        const transport = transportForId(peer, transportId);
        if (!transport) {
          reject(404, 'No such transport');
          return;
        }
        await transport.connect({ dtlsParameters });
        accept();
        return;
      }

      case 'produce': {
        const { transportId, kind, rtpParameters } = request.data as {
          transportId: string;
          kind: MediaKind;
          rtpParameters: RtpParameters;
        };
        const transport = transportForId(peer, transportId);
        if (!transport || transport !== peer.sendTransport) {
          reject(404, 'No such send transport');
          return;
        }
        // Simulcast is a client-side concern: the mediasoup-client Transport.produce()
        // call already set multiple encodings for video before ever reaching here, so
        // rtpParameters already carries them — nothing extra to add server-side.
        const producer = await transport.produce({ kind, rtpParameters });
        peer.producers.set(producer.id, producer);
        producer.on('transportclose', () => {
          peer.producers.delete(producer.id);
        });
        accept({ id: producer.id });

        // Tell everyone else in the room a new producer exists so they can consume it.
        room.notifyOthers(
          'newProducer',
          { peerId: peer.id, displayName: peer.displayName, producerId: producer.id, kind: producer.kind },
          peer.id
        );
        return;
      }

      case 'consume': {
        const { producerId, rtpCapabilities } = request.data as {
          producerId: string;
          rtpCapabilities: Parameters<typeof room.router.canConsume>[0]['rtpCapabilities'];
        };
        if (!room.router.canConsume({ producerId, rtpCapabilities })) {
          reject(400, 'Cannot consume this producer with the given rtpCapabilities');
          return;
        }
        const transport = peer.recvTransport;
        if (!transport) {
          reject(400, 'No recv transport yet');
          return;
        }
        const consumer = await transport.consume({
          producerId,
          rtpCapabilities,
          // Starts paused — client resumes once it has the Consumer set up
          // locally, so the first frames it ever receives are ones it can
          // actually render (see mediasoup's own recommended pattern, ConsumerOptions.paused doc comment).
          paused: true,
        });
        peer.consumers.set(consumer.id, consumer);
        consumer.on('transportclose', () => {
          peer.consumers.delete(consumer.id);
        });
        consumer.on('producerclose', () => {
          peer.consumers.delete(consumer.id);
          peer.protooPeer.notify('producerClosed', { producerId }).catch(() => {});
        });
        accept({
          id: consumer.id,
          producerId,
          kind: consumer.kind,
          rtpParameters: consumer.rtpParameters,
        });
        return;
      }

      case 'resumeConsumer': {
        const { consumerId } = request.data as { consumerId: string };
        const consumer = peer.consumers.get(consumerId);
        if (!consumer) {
          reject(404, 'No such consumer');
          return;
        }
        await consumer.resume();
        accept();
        return;
      }

      case 'closeProducer': {
        const { producerId } = request.data as { producerId: string };
        const producer = peer.producers.get(producerId);
        if (!producer) {
          reject(404, 'No such producer');
          return;
        }
        producer.close();
        peer.producers.delete(producerId);
        room.notifyOthers('producerClosed', { producerId }, peer.id);
        accept();
        return;
      }

      case 'getExistingProducers': {
        const existing: Array<{ peerId: string; displayName: string; producerId: string; kind: MediaKind }> = [];
        for (const other of room.peers.values()) {
          if (other.id === peer.id) continue;
          for (const producer of other.producers.values()) {
            existing.push({ peerId: other.id, displayName: other.displayName, producerId: producer.id, kind: producer.kind });
          }
        }
        accept({ producers: existing });
        return;
      }

      /**
       * Fired once by whichever client just discovered it created a brand
       * new room (see protooServer.ts's `created` flag) — forwards to
       * messaging-service so the rest of the group gets pushed/STOMP
       * invites. sfu-service trusts the memberIds list only as far as
       * forwarding it; messaging-service is the one that actually validates
       * it against real group membership before acting.
       */
      case 'notifyInvite': {
        const { memberIds, callerName, callType } = request.data as {
          memberIds: string[];
          callerName: string;
          callType: 'AUDIO' | 'VIDEO';
        };
        await notifyGroupCallInvite({
          groupId: room.groupId,
          callerId: peer.userId,
          callerName,
          memberIds,
          callType,
        });
        accept();
        return;
      }

      default:
        reject(400, `Unknown method "${request.method}"`);
    }
  } catch (e) {
    console.warn(`[requestHandlers] ${request.method} failed`, e);
    reject(500, e instanceof Error ? e.message : 'Internal error');
  }
}

function transportForId(peer: GroupCallPeer, transportId: string) {
  if (peer.sendTransport?.id === transportId) return peer.sendTransport;
  if (peer.recvTransport?.id === transportId) return peer.recvTransport;
  return null;
}
