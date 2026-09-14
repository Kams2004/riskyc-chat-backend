package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByConversationIdOrderBySentAtAsc(String conversationId);

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
}
