import { config } from '../config.js';

/**
 * The ONLY coupling between sfu-service and the Spring backend — two narrow
 * internal REST calls, both protected by the same X-Internal-Api-Key shared
 * secret already used for auth-service<->messaging-service traffic (see
 * SessionController.java's revoked-jtis endpoint for the existing
 * precedent). sfu-service never touches Postgres directly.
 */
function internalHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (config.internalApiKey) {
    headers['X-Internal-Api-Key'] = config.internalApiKey;
  }
  return headers;
}

export type GroupCallInvitePayload = {
  groupId: string;
  callerId: string;
  callerName: string;
  memberIds: string[];
  callType: 'AUDIO' | 'VIDEO';
};

/**
 * Fires when a room is first created (i.e. this IS the call starting).
 * messaging-service validates memberIds against its own GroupMemberRepository
 * before acting — sfu-service deliberately does not trust this list itself,
 * it just forwards what the starting client already had for its own UI.
 */
export async function notifyGroupCallInvite(payload: GroupCallInvitePayload): Promise<void> {
  try {
    const response = await fetch(`${config.messagingServiceInternalUrl}/internal/group-calls/invite`, {
      method: 'POST',
      headers: internalHeaders(),
      body: JSON.stringify(payload),
    });
    if (!response.ok) {
      console.warn(`[messagingClient] invite notify failed: ${response.status}`);
    }
  } catch (e) {
    console.warn('[messagingClient] invite notify failed', e);
  }
}

export type GroupCallEndedPayload = {
  groupId: string;
  callType: 'AUDIO' | 'VIDEO';
  startedAt: string;
  endedAt: string;
  participantIds: string[];
};

/** Fires when a room's last peer leaves — logs the call-summary chat message. */
export async function notifyGroupCallEnded(payload: GroupCallEndedPayload): Promise<void> {
  try {
    const response = await fetch(`${config.messagingServiceInternalUrl}/internal/group-calls/ended`, {
      method: 'POST',
      headers: internalHeaders(),
      body: JSON.stringify(payload),
    });
    if (!response.ok) {
      console.warn(`[messagingClient] ended notify failed: ${response.status}`);
    }
  } catch (e) {
    console.warn('[messagingClient] ended notify failed', e);
  }
}
