package com.riskyc.messaging.service;

import com.riskyc.messaging.entity.GroupInvitation;
import com.riskyc.messaging.repository.GroupInvitationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A group invitation left PENDING forever would otherwise sit as a live
 * "Accept"/"Decline" card in the invitee's conversation with the inviter
 * indefinitely, just as actionable a year later as the day it was sent. This
 * sweeps out anything nobody responded to within EXPIRY_WINDOW, matching
 * CallTimeoutJob's own pattern for the same kind of "nobody ever closed this
 * out" cleanup, and updates the card's status so both sides see it resolve.
 */
@Component
public class GroupInvitationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(GroupInvitationExpiryJob.class);

    private static final Duration EXPIRY_WINDOW = Duration.ofDays(3);

    private final GroupInvitationRepository invitationRepository;
    private final GroupInvitationMessageService invitationMessageService;

    public GroupInvitationExpiryJob(GroupInvitationRepository invitationRepository,
                                     GroupInvitationMessageService invitationMessageService) {
        this.invitationRepository = invitationRepository;
        this.invitationMessageService = invitationMessageService;
    }

    /** Once an hour is plenty — unlike a ringing call, nobody is staring at the screen waiting for this to resolve within seconds. */
    @Scheduled(fixedRate = 3_600_000)
    public void sweep() {
        List<GroupInvitation> stale = invitationRepository.findByStatusAndCreatedAtBefore(
                GroupInvitation.Status.PENDING, Instant.now().minus(EXPIRY_WINDOW));
        for (GroupInvitation invitation : stale) {
            invitation.setStatus(GroupInvitation.Status.EXPIRED);
            invitation.setRespondedAt(Instant.now());
            invitationRepository.save(invitation);
            invitationMessageService.updateInviteMessageStatus(invitation.getId(), GroupInvitation.Status.EXPIRED);
        }
        if (!stale.isEmpty()) {
            log.info("Expired {} stale group invitation(s)", stale.size());
        }
    }
}
