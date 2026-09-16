/**
 * Same base-URL/env-var conventions as the Java services' application.yml
 * defaults (see each service's src/main/resources/application.yml) — blank/
 * dev-safe defaults, overridden via env vars in docker-compose.yml.
 *
 * IMPORTANT deviation from every other service: this container runs with
 * `network_mode: host` (see docker-compose.yml's own comment on why — the
 * mediasoup WebRtcServer's UDP/TCP ports must be directly reachable, not
 * NAT'd through Docker's bridge, same reasoning as the existing `coturn`
 * service). That means this process does NOT sit on the compose bridge
 * network, so it can't reach auth-service/messaging-service by their
 * internal Docker hostnames (`http://auth-service:8081`) the way
 * RevokedJtiCache.java does — it reaches them via the HOST-mapped ports
 * instead (127.0.0.1:8091 / 127.0.0.1:8092), since host networking means
 * "this container's network IS the host's."
 */
export const config = {
  port: parseInt(process.env.SFU_PORT ?? '8095', 10),

  /** Public IP the mediasoup WebRtcServer announces in ICE candidates — clients connect here directly. */
  announcedIp: process.env.SFU_ANNOUNCED_IP ?? '127.0.0.1',
  webRtcServerPortBase: parseInt(process.env.SFU_WEBRTC_SERVER_PORT_BASE ?? '50000', 10),
  workerCount: parseInt(process.env.SFU_WORKER_COUNT ?? '2', 10),

  /** Same base64-encoded HMAC secret as backend/common/.../JwtIssuer.java — see auth/jwt.ts for the decode step. */
  jwtSecretBase64:
    process.env.RISKYC_JWT_SECRET ?? 'ZmFrZS1kZXYtc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZC1wbGVhc2UtY2hhbmdlLW1l',

  internalApiKey: process.env.RISKYC_INTERNAL_API_KEY ?? '',
  authServiceInternalUrl: process.env.RISKYC_AUTH_SERVICE_INTERNAL_URL ?? 'http://127.0.0.1:8091',
  messagingServiceInternalUrl: process.env.RISKYC_MESSAGING_SERVICE_INTERNAL_URL ?? 'http://127.0.0.1:8092',

  /** Existing coturn TURN server — passed to mediasoup-client as a fallback relay (see architecture notes). */
  turnServerUrl: process.env.RISKYC_TURN_SERVER_URL ?? 'turn:127.0.0.1:3478',
  turnUsername: process.env.RISKYC_TURN_USERNAME ?? 'riskyc',
  turnCredential: process.env.RISKYC_TURN_CREDENTIAL ?? 'riskyc-turn-secret',
} as const;
