package com.riskyc.common.dto;

/** One item of a multi-attachment (gallery) message — see MessageEnvelope#attachments. */
public record AttachmentDto(
        int position,
        String mediaType,
        String mediaObjectKey,
        String mediaFileName,
        Integer mediaDurationMs,
        /** Bytes — client-supplied at send time (it already has the file in hand pre-upload), never re-derived server-side. Null for an item sent before this field existed. Feeds the combined-size "Download · N photos" gate shown before a multi-item gallery has been fetched. */
        Long mediaFileSize
) {
}
