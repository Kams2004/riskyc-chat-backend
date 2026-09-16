/**
 * protoo-server ships no TypeScript types (plain CJS, last published for
 * bare JS consumers) — this is a minimal ambient declaration covering only
 * the surface this service actually uses, typed by reading its source
 * directly (node_modules/protoo-server/lib/{Room,Peer,transports/WebSocketServer}.js)
 * rather than guessing.
 */
declare module 'protoo-server' {
  import type { Server as HttpServer } from 'node:http';
  import type { EventEmitter } from 'node:events';

  export interface ProtooRequest {
    request: true;
    id: number;
    method: string;
    data?: Record<string, unknown>;
  }

  export interface ProtooNotification {
    notification: true;
    method: string;
    data?: Record<string, unknown>;
  }

  export class Peer extends EventEmitter {
    readonly id: string;
    readonly closed: boolean;
    /** Freeform per-peer storage — this service stashes userId/displayName/groupId here. */
    data: Record<string, unknown>;
    close(): void;
    request(method: string, data?: Record<string, unknown>): Promise<Record<string, unknown>>;
    notify(method: string, data?: Record<string, unknown>): Promise<void>;
    on(event: 'close', listener: () => void): this;
    on(
      event: 'request',
      listener: (
        request: ProtooRequest,
        accept: (data?: Record<string, unknown>) => void,
        reject: (errorCode: number, errorReason?: string) => void
      ) => void
    ): this;
    on(event: 'notification', listener: (notification: ProtooNotification) => void): this;
  }

  export class Room extends EventEmitter {
    readonly closed: boolean;
    readonly peers: Peer[];
    close(): void;
    createPeer(peerId: string, transport: unknown): Peer;
    hasPeer(peerId: string): boolean;
    getPeer(peerId: string): Peer | undefined;
  }

  export interface ConnectionRequestInfo {
    request: import('node:http').IncomingMessage;
    origin: string;
    socket: import('node:net').Socket;
  }

  export class WebSocketServer extends EventEmitter {
    constructor(httpServer: HttpServer, options?: Record<string, unknown>);
    stop(): void;
    on(
      event: 'connectionrequest',
      listener: (
        info: ConnectionRequestInfo,
        accept: () => unknown,
        reject: (code: number, reason: string) => void
      ) => void
    ): this;
  }
}
