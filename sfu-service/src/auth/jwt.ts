import jwt from 'jsonwebtoken';

import { config } from '../config.js';

export type JwtClaims = {
  userId: string;
  jti: string;
};

/**
 * Mirrors backend/common/.../security/JwtIssuer.java exactly: the config
 * value is a BASE64-encoded string, and the actual HMAC key bytes are the
 * base64-*decoded* value (JwtIssuer does
 * `Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret))`) — not the
 * raw config string itself. Claims: `sub` = userId, `jti` = session id.
 */
const signingKey = Buffer.from(config.jwtSecretBase64, 'base64');

export class InvalidTokenError extends Error {}

export function verifyToken(token: string): JwtClaims {
  let payload: jwt.JwtPayload;
  try {
    const decoded = jwt.verify(token, signingKey, { algorithms: ['HS256'] });
    if (typeof decoded === 'string') {
      throw new InvalidTokenError('Unexpected string payload');
    }
    payload = decoded;
  } catch {
    throw new InvalidTokenError('Invalid or expired token');
  }
  if (!payload.sub || !payload.jti) {
    throw new InvalidTokenError('Missing sub/jti claim');
  }
  return { userId: payload.sub, jti: payload.jti };
}
