package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.SavedSticker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SavedStickerRepository extends JpaRepository<SavedSticker, Long> {
    List<SavedSticker> findByUserIdOrderBySavedAtDesc(String userId);

    boolean existsByUserIdAndObjectKey(String userId, String objectKey);

    @Modifying
    @Transactional
    void deleteByUserIdAndObjectKey(String userId, String objectKey);
}
