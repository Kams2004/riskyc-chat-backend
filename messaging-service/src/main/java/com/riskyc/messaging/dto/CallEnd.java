package com.riskyc.messaging.dto;

/**
 * reason is one of "declined", "cancelled", "hangup", "busy" (client's
 * intent) or "answered-elsewhere"/"timeout" (server-synthesized — see
 * CallController#answer and CallTimeoutJob) — the server derives the
 * persisted CallStatus from it plus whether the call was ever answered.
 * fromDeviceId — see CallInvite's doc comment; null on a server-synthesized
 * end (no real device originated it), present when relaying a real client
 * CallAnswer's device id for the "answered-elsewhere" signal so a device's
 * own echo of its own answer can be told apart from a sibling's.
 */
public record CallEnd(String callId, String fromUserId, String reason, String fromDeviceId) {
}
