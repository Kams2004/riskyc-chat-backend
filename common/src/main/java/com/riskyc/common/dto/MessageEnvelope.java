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
        boolean edited,
        boolean deleted,
        String groupId,
        boolean forwarded,
        List<AttachmentDto> attachments,
        String replyToMessageId,
        String replyToConversationId,
        String replyToSenderId,
        String replyToSnippet,
        boolean pinned
) {
}
