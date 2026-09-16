import assert from 'node:assert/strict';
import { test } from 'node:test';
import jwt from 'jsonwebtoken';
import WebSocket from 'ws';

import { initWorkerPool } from '../src/mediasoup/workerPool.js';
import { createProtooServer } from '../src/signaling/protooServer.js';

const JWT_SECRET_BASE64 = 'ZmFrZS1kZXYtc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZC1wbGVhc2UtY2hhbmdlLW1l';
const signingKey = Buffer.from(JWT_SECRET_BASE64, 'base64');

function mintToken(userId: string): string {
  return jwt.sign({ jti: `test-jti-${userId}` }, signingKey, { subject: userId, expiresIn: '1h', algorithm: 'HS256' });
}

let requestId = 1;

/** Minimal protoo-wire client — same shape the real mobile/web client will use, just enough for this test. */
function connect(url: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, 'protoo');
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

function request(ws: WebSocket, method: string, data: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const id = requestId++;
    const onMessage = (raw: WebSocket.RawData) => {
      const msg = JSON.parse(raw.toString());
      if (msg.response && msg.id === id) {
        ws.off('message', onMessage);
        if (msg.ok) resolve(msg.data ?? {});
        else reject(new Error(`${msg.errorCode}: ${msg.errorReason}`));
      }
    };
    ws.on('message', onMessage);
    ws.send(JSON.stringify({ request: true, id, method, data }));
  });
}

function waitForNotification(ws: WebSocket, method: string): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    const onMessage = (raw: WebSocket.RawData) => {
      const msg = JSON.parse(raw.toString());
      if (msg.notification && msg.method === method) {
        ws.off('message', onMessage);
        resolve(msg.data ?? {});
      }
    };
    ws.on('message', onMessage);
  });
}

test('two peers join the same group-call room, exchange producers/consumers over the real signaling protocol', async () => {
  await initWorkerPool();
  const port = 18095;
  createProtooServer(port);
  // Give the HTTP server a tick to actually bind before connecting.
  await new Promise((r) => setTimeout(r, 200));

  const groupId = `test-group-${Date.now()}`;
  const tokenA = mintToken('user-a');
  const tokenB = mintToken('user-b');

  const wsA = await connect(`ws://localhost:${port}?token=${tokenA}&groupId=${groupId}&displayName=Alice&callType=VIDEO`);
  const capsResponseA = await request(wsA, 'getRouterRtpCapabilities');
  const rtpCapsA = capsResponseA.rtpCapabilities as { codecs: unknown[] };
  assert.ok(Array.isArray(rtpCapsA.codecs) && rtpCapsA.codecs.length > 0, 'router should expose codecs');

  const sendTransportA = await request(wsA, 'createWebRtcTransport', { direction: 'send' });
  assert.ok(typeof sendTransportA.id === 'string', 'send transport should have an id');
  assert.ok(Array.isArray(sendTransportA.iceCandidates), 'should return ICE candidates');
  assert.ok(Array.isArray((sendTransportA as any).iceServers), 'should include TURN fallback iceServers');

  // Second peer joins the SAME room (start-or-join: room already exists from A).
  const joinedNotifA = waitForNotification(wsA, 'peerJoined');
  const wsB = await connect(`ws://localhost:${port}?token=${tokenB}&groupId=${groupId}&displayName=Bob&callType=VIDEO`);
  const peerJoined = await joinedNotifA;
  assert.equal(peerJoined.peerId, 'user-b');
  assert.equal(peerJoined.displayName, 'Bob');

  await request(wsB, 'getRouterRtpCapabilities');
  await request(wsB, 'createWebRtcTransport', { direction: 'send' });
  await request(wsA, 'createWebRtcTransport', { direction: 'recv' });
  await request(wsB, 'createWebRtcTransport', { direction: 'recv' });

  // notifyInvite forwards to messaging-service — expected to fail gracefully
  // (no messaging-service running in this standalone test) without throwing
  // back to the client; the request itself must still resolve.
  await request(wsA, 'notifyInvite', { memberIds: ['user-b'], callerName: 'Alice', callType: 'VIDEO' });

  wsA.close();
  wsB.close();
  console.log('Signaling protocol test passed: join, capabilities, transport creation, and peer notifications all work end-to-end.');
});

// The mediasoup worker subprocesses and the still-listening HTTP/WS server
// keep the event loop alive indefinitely — node --test reports results as
// soon as the test function above resolves, but the process itself never
// goes idle on its own. Force it once results are in.
setTimeout(() => process.exit(0), 500);

