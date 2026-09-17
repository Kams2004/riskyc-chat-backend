package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.StatusView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StatusViewRepository extends JpaRepository<StatusView, Long> {

    Optional<StatusView> findByStatusIdAndViewerId(String statusId, String viewerId);

    List<StatusView> findByStatusId(String statusId);

    List<StatusView> findByStatusIdInAndViewerId(Collection<String> statusIds, String viewerId);

    @Modifying
    @Transactional
    void deleteByStatusIdIn(Collection<String> statusIds);
}
