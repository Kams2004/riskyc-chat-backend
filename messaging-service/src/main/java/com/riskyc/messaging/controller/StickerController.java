package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.entity.SavedSticker;
import com.riskyc.messaging.repository.SavedStickerRepository;
import com.riskyc.messaging.security.RevokedJtiCache;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Each user's own sticker collection — built up either by "Create sticker"
 * (turning a picked image into a reusable sticker) or by saving one someone
 * else sent. A sticker itself is just an ordinary MinIO object referenced by
 * objectKey (uploaded through the same media-service presigned-URL flow as
 * every other attachment); this only tracks which objectKeys a given user
 * has chosen to keep, same one-row-per-(user, thing) shape as
 * MutedConversationRepository.
 */
@RestController
@RequestMapping("/api/stickers")
public class StickerController {

    private final SavedStickerRepository savedStickerRepository;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public StickerController(SavedStickerRepository savedStickerRepository, JwtIssuer jwtIssuer,
                              RevokedJtiCache revokedJtiCache) {
        this.savedStickerRepository = savedStickerRepository;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record SaveStickerRequest(String objectKey) {
    }

    public record StickerItem(String objectKey, String savedAt) {
    }

    @GetMapping
    public List<StickerItem> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        return savedStickerRepository.findByUserIdOrderBySavedAtDesc(userId).stream()
                .map(s -> new StickerItem(s.getObjectKey(), s.getSavedAt().toString()))
                .toList();
    }

    /** Idempotent — saving an objectKey already in the collection is a no-op, not a duplicate row or an error. */
    @PostMapping
    public StickerItem save(@RequestBody SaveStickerRequest request,
                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        if (request.objectKey() == null || request.objectKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectKey is required");
        }
        if (!savedStickerRepository.existsByUserIdAndObjectKey(userId, request.objectKey())) {
            savedStickerRepository.save(new SavedSticker(userId, request.objectKey(), Instant.now()));
        }
        return new StickerItem(request.objectKey(), Instant.now().toString());
    }

    @DeleteMapping("/{objectKey}")
    public void remove(@PathVariable String objectKey,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        savedStickerRepository.deleteByUserIdAndObjectKey(userId, objectKey);
    }

    private String callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            if (revokedJtiCache.isRevoked(claims.jti())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims.subject();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
