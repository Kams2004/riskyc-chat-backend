package com.riskyc.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * Wire format exchanged over WebSocket and Kafka. {@code ciphertext} is opaque
 * to every server-side component once end-to-end encryption is introduced;
 * until then it carries plaintext — either the whole message body for a
 * plain-text message, or a caption (possibly empty) alongside a media
 * attachment. {@code mediaType}/{@code mediaObjectKey}/{@code mediaFileName}/
 * {@code mediaDurationMs} are all null for a plain-text message, and stay the
 * single-attachment path (voice notes, one image/file) — {@code attachments}
 * is populated instead for a multi-image/video gallery send, empty/null
 * otherwise. A message never uses both.
 */
public record MessageEnvelope(
        String messageId,
        String conversationId,
        String senderId,
        String recipientId,
        String ciphertext,
        Instant sentAt,
        String status,
        String mediaType,
        String mediaObjectKey,
        String mediaFileName,
        Integer mediaDurationMs,
        /** Comma-separated normalized amplitude samples for an AUDIO message — see Message.java's own field comment. Null for every non-voice message. */
        String waveform,
        /** Opaque drawing/text-overlay JSON for an IMAGE message — see Message.java's own field comment. Null for every message without one. */
        String overlayJson,
        boolean edited,
        boolean deleted,
        String groupId,
        boolean forwarded,
        List<AttachmentDto> attachments,
        String replyToMessageId,
        String replyToConversationId,
        String replyToSenderId,
        String replyToSnippet,
        boolean pinned,
        /** Only set on a group-call log entry — see Message.java's own field comment. */
        Integer mediaParticipantCount,
        /**
         * Client-supplied at send time (the sender already knows their own
         * current display name from AuthContext) — used as the push
         * notification's title for a 1:1 message instead of a generic
         * "RiskyC Chat", without messaging-service needing to call back into
         * auth-service just to resolve it. Null-safe: falls back to the
         * generic title wherever it's used. Not persisted on Message itself
         * (a display name can change after the fact; this is a delivery-time
         * label, not part of the message's own record).
         */
        String senderDisplayName,
        /** Null for a normal message. Set when the conversation had disappearing messages enabled at send time — see Message.java's own field comment. */
        Instant expiresAt,
        /** True for a group event log line ("X joined the group"), never something a person typed — see Message.java's own field comment. */
        boolean system,
        /** Both null unless this message is a reply to a status — see Message.java's own field comment. */
        String replyToStatusId,
        String replyToStatusOwnerId,
        /** Bytes, client-supplied at send time — see Message.java's own field comment. Null for a message sent before this field existed, or one with no single-attachment media at all. */
        Long mediaFileSize
) {
}
