import { config } from '../config.js';

/**
 * Mirrors backend/messaging-service/.../security/RevokedJtiCache.java: polls
 * auth-service's existing internal endpoint every 30s, accumulating revoked
 * jtis into an in-memory set. A live call is worth this — unlike
 * presence-service/media-service (which skip revocation checking entirely as
 * a documented MVP tradeoff, since they're Postgres-free and lower-stakes),
 * this is cheap to replicate and a signed-out session shouldn't be able to
 * keep joining/producing media in an active call.
 */
const POLL_INTERVAL_MS = 30_000;

const revokedJtis = new Set<string>();
let lastPolledAt = new Date(0).toISOString();

async function poll(): Promise<void> {
  const since = lastPolledAt;
  const now = new Date().toISOString();
  try {
    const url = `${config.authServiceInternalUrl}/internal/sessions/revoked-jtis?since=${encodeURIComponent(since)}`;
    const headers: Record<string, string> = {};
    if (config.internalApiKey) {
      headers['X-Internal-Api-Key'] = config.internalApiKey;
    }
    const response = await fetch(url, { headers });
    if (!response.ok) {
      console.warn(`[revokedJtiPoller] auth-service returned ${response.status}`);
      return;
    }
    const jtis = (await response.json()) as string[];
    for (const jti of jtis) {
      revokedJtis.add(jti);
    }
    lastPolledAt = now;
  } catch (e) {
    console.warn('[revokedJtiPoller] poll failed', e);
  }
}

export function startRevokedJtiPolling(): void {
  poll();
  setInterval(poll, POLL_INTERVAL_MS);
}

export function isRevoked(jti: string): boolean {
  return revokedJtis.has(jti);
}
