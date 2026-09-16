import { config } from './config.js';
import { startRevokedJtiPolling } from './auth/revokedJtiPoller.js';
import { initWorkerPool } from './mediasoup/workerPool.js';
import { createProtooServer } from './signaling/protooServer.js';

async function main(): Promise<void> {
  await initWorkerPool();
  startRevokedJtiPolling();
  createProtooServer(config.port);
}

main().catch((e) => {
  console.error('[sfu-service] fatal startup error', e);
  process.exit(1);
});
