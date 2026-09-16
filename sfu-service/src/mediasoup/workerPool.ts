import * as mediasoup from 'mediasoup';
import type { Worker, WebRtcServer } from 'mediasoup/types';

import { config } from '../config.js';
import { workerSettings } from './mediasoupConfig.js';

/**
 * A mediasoup Worker is a single-threaded C++ process — one Worker hosting
 * every Router in the app is a throughput ceiling under concurrent group
 * calls, so this runs a small pool (default 2) and assigns new rooms
 * round-robin across them at room-creation time (see roomManager.ts).
 *
 * Each Worker gets its own WebRtcServer bound to ONE shared UDP+TCP port
 * pair, which every WebRtcTransport created on that Worker reuses — this is
 * what keeps the exposed port count at "one pair per worker" instead of
 * needing a wide port range sized to peak concurrent transports (mediasoup
 * docs: WebRtcServer "brings to WebRtcTransports the ability to listen on a
 * single UDP/TCP port... per mediasoup Worker").
 */
type WorkerEntry = {
  worker: Worker;
  webRtcServer: WebRtcServer;
};

const workers: WorkerEntry[] = [];
let nextWorkerIndex = 0;

export async function initWorkerPool(): Promise<void> {
  for (let i = 0; i < config.workerCount; i++) {
    const port = config.webRtcServerPortBase + i;
    const worker = await mediasoup.createWorker(workerSettings);

    worker.on('died', (error) => {
      console.error(`[workerPool] mediasoup worker ${worker.pid} died, exiting`, error);
      // A dead worker leaves every room it hosted with a router that can no
      // longer create transports — there's no clean in-process recovery for
      // that, so exit and let the container orchestrator restart fresh
      // (same fail-fast philosophy documented for other MVP-stage gaps in
      // this codebase — see e.g. PushNotificationService's fire-and-forget
      // error logging).
      process.exit(1);
    });

    const webRtcServer = await worker.createWebRtcServer({
      listenInfos: [
        { protocol: 'udp', ip: '0.0.0.0', announcedAddress: config.announcedIp, port },
        { protocol: 'tcp', ip: '0.0.0.0', announcedAddress: config.announcedIp, port },
      ],
    });

    workers.push({ worker, webRtcServer });
    console.log(`[workerPool] worker ${i} ready (pid ${worker.pid}, WebRtcServer port ${port})`);
  }
}

/** Round-robin assignment — called once per room at creation time (see roomManager.ts). */
export function nextWorker(): WorkerEntry {
  const entry = workers[nextWorkerIndex];
  nextWorkerIndex = (nextWorkerIndex + 1) % workers.length;
  return entry;
}
