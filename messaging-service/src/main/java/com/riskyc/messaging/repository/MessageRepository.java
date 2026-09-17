package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByConversationIdOrderBySentAtAsc(String conversationId);

    List<Message> findByConversationIdAndMediaTypeInAndDeletedFalseOrderBySentAtDesc(String conversationId,
                                                                                       List<Message.MediaType> mediaTypes);

    /**
     * ILIKE, not a tsvector/full-text index — this app has no message-search
     * volume yet to justify one, and ciphertext is still plaintext today (no
     * E2E encryption implemented yet, see MessageEnvelope's own doc comment)
     * so a plain substring match is viable. Scoped to one conversationId,
     * never a global search, so the caller's own membership check is enough
     * authorization (see MessageHistoryController#search).
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deleted = false " +
            "AND m.ciphertext ILIKE CONCAT('%', :query, '%') ORDER BY m.sentAt DESC")
    List<Message> searchInConversation(@Param("conversationId") String conversationId, @Param("query") String query);

    interface OneToOneSummaryRow {
        String getConversationId();
        String getOtherUserId();
        Instant getLastMessageAt();
    }

    /**
     * One row per 1:1 conversation this user has exchanged a message in
     * (as either sender or recipient), with the other party's id and the
     * most recent message's timestamp — everything the client needs to
     * create a local conversation stub for one it never saw live over
     * STOMP (see ConversationController and mobile's inboxSocket sync).
     */
    @Query(value = """
            SELECT DISTINCT ON (m.conversation_id)
              m.conversation_id AS conversationId,
              CASE WHEN m.sender_id = :userId THEN m.recipient_id ELSE m.sender_id END AS otherUserId,
              m.sent_at AS lastMessageAt
            FROM message m
            WHERE m.group_id IS NULL AND (m.sender_id = :userId OR m.recipient_id = :userId)
            ORDER BY m.conversation_id, m.sent_at DESC
            """, nativeQuery = true)
    List<OneToOneSummaryRow> findOneToOneSummariesForUser(@Param("userId") String userId);

    @Query("SELECT MAX(m.sentAt) FROM Message m WHERE m.groupId = :groupId")
    Instant findLastMessageAtForGroup(@Param("groupId") String groupId);

    /** Feeds DisappearingMessageCleanupJob's periodic hard-delete sweep. History reads already filter these out themselves in the meantime — see MessageHistoryController#history. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Message m WHERE m.expiresAt IS NOT NULL AND m.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
