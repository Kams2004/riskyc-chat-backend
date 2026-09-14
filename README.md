# riskyc-chat backend

Maven multi-module Spring Boot backend for the RiskyC chat app. See the top-level
architecture proposal in the project conversation history for the full design;
this is the phase-1 MVP skeleton (auth + real-time 1:1 messaging + presence +
media, no E2E encryption yet, no Kafka yet — see roadmap below).

## Modules

- `common` — shared DTOs (`MessageEnvelope`, `UserSummary`) and `JwtIssuer`, used by every service.
- `auth-service` (port 8081) — phone-or-email OTP login, issues JWTs. Phone OTP is still logged to stdout (stub — swap for an SMS provider before shipping); email OTP is real, sent via SMTP (see "Sending real OTP emails" below).
- `messaging-service` (port 8082) — STOMP-over-WebSocket at `/ws`, persists messages to Postgres, broadcasts to `/topic/conversation.{id}`. `GET /api/messages/{conversationId}` returns history.
- `media-service` (port 8083) — issues pre-signed MinIO upload/download URLs so the mobile client talks to MinIO directly.
- `presence-service` (port 8084) — online/last-seen tracked in Redis so it's consistent across replicas.

## Running locally

```bash
cd backend
mvn clean package -DskipTests
docker compose up --build
```

This brings up Postgres, Redis, MinIO, and all four services. MinIO console is at http://localhost:9001 (riskyc / riskyc-secret).

## Sending real OTP emails (Gmail SMTP)

`auth-service` sends OTP emails over plain SMTP (`spring-boot-starter-mail`), configured entirely
through environment variables — no code changes needed to switch providers later. To use Gmail:

1. Turn on 2-Step Verification on the Google account you'll send from, if it isn't already: https://myaccount.google.com/signinoptions/two-step-verification
2. Go to https://myaccount.google.com/apppasswords (only appears once 2-Step Verification is on).
3. Create an app password — name it something like "RiskyC Chat" — and copy the 16-character password it shows you (spaces don't matter). This is **not** your normal Gmail password; Google only shows it once.
4. Copy `backend/.env.example` to `backend/.env` and fill in:
   ```
   RISKYC_MAIL_USERNAME=your-address@gmail.com
   RISKYC_MAIL_PASSWORD=the-16-character-app-password
   RISKYC_MAIL_FROM=your-address@gmail.com
   ```
5. Run `docker compose --env-file .env up --build` (or `export $(cat .env | xargs)` first, then plain `docker compose up`).

Gmail SMTP caps out around 500 emails/day and isn't meant for high-volume production sending — when
you outgrow it, point the same four env vars at a transactional provider's SMTP relay (SendGrid,
Mailgun, SES all offer one) instead; `EmailOtpSender` doesn't change.

**API shape**: `POST /api/auth/otp/request` and `POST /api/auth/otp/verify` now take either
`{"phoneNumber": "..."}` or `{"email": "..."}` — exactly one, never both — as the identifier.

## Known gaps (by design, not oversight)

- **No end-to-end encryption yet.** `ciphertext` fields currently carry plaintext. Signal Protocol integration is a separate, larger effort — see phase 2/3 in the architecture proposal.
- **No per-recipient WebSocket routing.** Messages broadcast to a conversation topic rather than `convertAndSendToUser`, because that requires wiring a JWT-authenticating STOMP handshake interceptor first.
- **No Kafka.** Single-node Redis/in-memory broker is enough until you need multi-node WS fan-out or service decoupling — introduce it in phase 3 rather than upfront.
- **auth-service OTP store is in-memory** — fine for one replica; move to Redis with TTL before scaling out.
