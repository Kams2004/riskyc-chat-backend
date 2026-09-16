import type { RouterRtpCodecCapability, WorkerLogLevel } from 'mediasoup/types';

/** Opus for audio, VP8 for video — the same widely-supported baseline every mediasoup example ships with; H264 can be added later if a platform needs hardware-encode fallback. */
export const mediaCodecs: RouterRtpCodecCapability[] = [
  {
    kind: 'audio',
    mimeType: 'audio/opus',
    clockRate: 48000,
    channels: 2,
  },
  {
    kind: 'video',
    mimeType: 'video/VP8',
    clockRate: 90000,
    parameters: {
      'x-google-start-bitrate': 1000,
    },
  },
];

// No rtcMinPort/rtcMaxPort here — those are for the legacy per-transport
// port-range allocation, deprecated in favor of WebRtcServer (see
// workerPool.ts), which is what every WebRtcTransport in this service uses
// exclusively, so that whole port range is simply unused.
export const workerSettings = {
  logLevel: 'warn' as WorkerLogLevel,
};

/**
 * Basic simulcast for video producers — three quality layers so consumers on
 * weaker connections (cellular, several participants at once) get a lower
 * layer instead of the SFU force-downscaling a single high-bitrate stream
 * for everyone. Audio producers don't pass `encodings` at all.
 */
export const videoSimulcastEncodings = [
  { rid: 'r0', maxBitrate: 100_000, scalabilityMode: 'S1T3' },
  { rid: 'r1', maxBitrate: 300_000, scalabilityMode: 'S1T3' },
  { rid: 'r2', maxBitrate: 900_000, scalabilityMode: 'S1T3' },
];
