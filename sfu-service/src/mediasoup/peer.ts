import type { Consumer, Producer, WebRtcTransport } from 'mediasoup/types';
import type { Peer as ProtooPeer } from 'protoo-server';

/**
 * One participant's media state within a GroupCallRoom. A protoo Peer alone
 * only carries signaling (request/notify) — this is where the actual
 * mediasoup objects for that participant live. Two transports (send/recv)
 * per mediasoup's own recommended pattern: one transport carries everything
 * this peer PRODUCES, the other everything it CONSUMES, so pausing/closing
 * one direction never disturbs the other.
 */
export class GroupCallPeer {
  readonly protooPeer: ProtooPeer;
  readonly userId: string;
  readonly displayName: string;

  sendTransport: WebRtcTransport | null = null;
  recvTransport: WebRtcTransport | null = null;
  readonly producers = new Map<string, Producer>();
  readonly consumers = new Map<string, Consumer>();

  constructor(protooPeer: ProtooPeer, userId: string, displayName: string) {
    this.protooPeer = protooPeer;
    this.userId = userId;
    this.displayName = displayName;
  }

  get id(): string {
    return this.protooPeer.id;
  }

  /** Closes every mediasoup object this peer owns — transports cascade-close their own producers/consumers. */
  closeMedia(): void {
    this.sendTransport?.close();
    this.recvTransport?.close();
    this.producers.clear();
    this.consumers.clear();
  }
}
