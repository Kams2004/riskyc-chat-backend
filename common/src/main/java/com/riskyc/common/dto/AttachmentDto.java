package com.riskyc.common.dto;

/** One item of a multi-attachment (gallery) message — see MessageEnvelope#attachments. */
public record AttachmentDto(
        int position,
        String mediaType,
        String mediaObjectKey,
        String mediaFileName,
        Integer mediaDurationMs
) {
}
