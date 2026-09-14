package com.riskyc.common.dto;

import java.time.Instant;

/**
 * Wire format exchanged over WebSocket and Kafka. {@code ciphertext} is opaque
 * to every server-side component once end-to-end encryption is introduced;
 * until then it carries plaintext — either the whole message body for a
 * plain-text message, or a caption (possibly empty) alongside a media
 * attachment. {@code mediaType}/{@code mediaObjectKey}/{@code mediaFileName}/
 * {@code mediaDurationMs} are all null for a plain-text message.
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
        String groupId
) {
}
