package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.dto.CallAnswer;
import com.riskyc.messaging.dto.CallEnd;
import com.riskyc.messaging.dto.CallIceCandidate;
import com.riskyc.messaging.dto.CallInvite;
import com.riskyc.messaging.dto.CallRenegotiateOffer;
import com.riskyc.messaging.dto.CallUsageReport;
import com.riskyc.messaging.entity.Call;
import com.riskyc.messaging.repository.CallRepository;
import com.riskyc.messaging.service.CallLogService;
import com.riskyc.messaging.service.PushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WebRTC signaling relay for 1:1 calls — everything lands on the same
 * /user/queue/calls destination (one always-on subscription mobile's
 * CallContext keeps open, mirroring InboxSocket's /queue/messages), with a
 * "callMessageType" header telling the client which of invite/answer/ice/end
 * it's looking at, since the payload shapes differ. No SFU/media relay here:
 * this only exchanges SDP/ICE between the two peers, who then connect
 * directly (see architecture notes — same peer-to-peer model WebRTC assumes,
 * degraded reliability without a TURN server off the caller/callee's shared
 * network, deliberately deferred).
 */
@Controller
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);

    private final CallRepository callRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;
    private final CallLogService callLogService;
    private final String internalApiKey;
    private final String authServiceInternalUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public CallController(CallRepository callRepository,
                           SimpMessagingTemplate messagingTemplate, PushNotificationService pushNotificationService,
                           JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache, CallLogService callLogService,
                           @Value("${riskyc.internal.api-key:}") String internalApiKey,
                           @Value("${riskyc.auth-service.internal-url}") String authServiceInternalUrl) {
        this.callRepository = callRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
        this.callLogService = callLogService;
        this.internalApiKey = internalApiKey;
        this.authServiceInternalUrl = authServiceInternalUrl;
    }

    /**
     * Same "another service calls auth-service's internal lookup" pattern as
     * SystemAccountController#fetchSystemAccountId — duplicated rather than
     * shared since neither controller has a common service layer to put it
     * in today. The official account is read-only/broadcast-only (see
     * SystemAccountController#broadcast's doc comment); nobody should be able
     * to ring it. The mobile client already hides the call buttons for it —
     * this is the server-side backstop for a client that skips that check.
     */
    private boolean isSystemAccount(String userId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (!internalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", internalApiKey);
            }
            URI uri = URI.create(authServiceInternalUrl + "/internal/system-account/id");
            var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<Void>(headers), Map.class);
            Object systemAccountId = response.getBody() != null ? response.getBody().get("userId") : null;
            return systemAccountId != null && systemAccountId.toString().equals(userId);
        } catch (RestClientException e) {
            log.warn("Failed to resolve the official account's id while validating a call invite", e);
            return false;
        }
    }

    @MessageMapping("/call.invite")
    public void invite(CallInvite inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        String fromUserId = principal.getName();
        if (isSystemAccount(inbound.toUserId())) {
            log.warn("Dropped call invite from {} to the official account {}", fromUserId, inbound.toUserId());
            return;
        }
        String callId = inbound.callId() != null ? inbound.callId() : UUID.randomUUID().toString();
        Call call = new Call(callId, fromUserId, inbound.toUserId(), Call.CallType.valueOf(inbound.type()), Instant.now());
        // Persisted so a notification tap can fetch a fresh offer via GET
        // /api/calls/{id} instead of relying on the (size-limited, possibly
        // stale-by-the-time-it's-tapped) push payload to carry it.
        call.setSdpOffer(inbound.sdpOffer());
        call.setCallerName(inbound.callerName());
        callRepository.save(call);

        CallInvite outbound = new CallInvite(callId, fromUserId, inbound.toUserId(), inbound.type(), inbound.sdpOffer(),
                inbound.callerName(), inbound.fromDeviceId());
        messagingTemplate.convertAndSendToUser(inbound.toUserId(), "/queue/calls", outbound, headersFor("invite"));

        // fromUserId/callerName/callType let a notification action (Answer/
        // Decline) resolve and act on this call without first opening the
        // app and waiting for the live STOMP invite to (re)arrive.
        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("type", "call");
        pushData.put("callId", callId);
        pushData.put("fromUserId", fromUserId);
        pushData.put("callerName", inbound.callerName());
        pushData.put("callType", inbound.type());
        String callerLabel = inbound.callerName() != null && !inbound.callerName().isBlank() ? inbound.callerName() : "Someone";
        String callKind = "VIDEO".equals(inbound.type()) ? "video call" : "voice call";
        pushNotificationService.sendToUser(inbound.toUserId(), callerLabel, "Incoming " + callKind, "calls-v3", pushData,
                "incoming_call");
    }

    /**
     * What a notification Answer/Decline action fetches the instant it's
     * tapped — deliberately minimal (no full Call history), and only
     * available while still RINGING so a stale/already-handled notification
     * can't be used to rejoin a call that moved on without this device.
     */
    @GetMapping("/api/calls/{callId}")
    @ResponseBody
    public CallSnapshot getCall(@PathVariable String callId,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such call"));
        if (!callerId.equals(call.getCallerId()) && !callerId.equals(call.getCalleeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant in this call");
        }
        if (call.getStatus() != Call.CallStatus.RINGING) {
            throw new ResponseStatusException(HttpStatus.GONE, "Call is no longer ringing");
        }
        return new CallSnapshot(call.getId(), call.getCallerId(), call.getCallerName(), call.getType().name(),
                call.getSdpOffer());
    }

    public record CallSnapshot(String callId, String fromUserId, String callerName, String type, String sdpOffer) {
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

    @MessageMapping("/call.answer")
    public void answer(CallAnswer inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            if (!principal.getName().equals(call.getCalleeId())) {
                return;
            }
            // If the callee is signed into multiple devices, more than one
            // could race to answer — only the first one to land here wins;
            // a second answer attempt for an already-ACCEPTED call is a
            // no-op (that sibling device learns the outcome from the
            // "answered-elsewhere" broadcast below instead).
            if (call.getStatus() != Call.CallStatus.RINGING) {
                return;
            }
            call.setStatus(Call.CallStatus.ACCEPTED);
            call.setAnsweredAt(Instant.now());
            callRepository.save(call);

            CallAnswer outbound = new CallAnswer(inbound.callId(), principal.getName(), inbound.sdpAnswer(), inbound.fromDeviceId());
            messagingTemplate.convertAndSendToUser(call.getCallerId(), "/queue/calls", outbound, headersFor("answer"));

            // Tell the callee's OWN other sessions (if any) to stop ringing
            // — reaches every connected device of this account via the same
            // convertAndSendToUser fan-out, including the device that just
            // answered itself; that device recognizes its own fromDeviceId
            // in the echo and ignores it, while siblings treat it as the
            // real "stop ringing, answered elsewhere" signal.
            CallEnd answeredElsewhere = new CallEnd(inbound.callId(), principal.getName(), "answered-elsewhere", inbound.fromDeviceId());
            messagingTemplate.convertAndSendToUser(call.getCalleeId(), "/queue/calls", answeredElsewhere, headersFor("end"));
        });
    }

    @MessageMapping("/call.ice")
    public void ice(CallIceCandidate inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String fromUserId = principal.getName();
            String toUserId = call.otherParty(fromUserId);
            CallIceCandidate outbound = new CallIceCandidate(inbound.callId(), fromUserId, inbound.candidate(),
                    inbound.sdpMid(), inbound.sdpMLineIndex());
            messagingTemplate.convertAndSendToUser(toUserId, "/queue/calls", outbound, headersFor("ice"));
        });
    }

    /**
     * ICE restart mid-call — the offerer (see mobile's isOffererRef) detects
     * its RTCPeerConnection reaching 'failed' (a real network change, not
     * just a transient 'disconnected' blip WebRTC often recovers from on its
     * own) and renegotiates a fresh offer/answer without tearing the call
     * down, same idea as a normal call app riding out a wifi-to-cellular
     * handoff instead of dropping the call. Pure relay, mirroring call.ice —
     * the call already exists and is mid-flight, so there's nothing to
     * persist here beyond what invite/answer already did.
     */
    @MessageMapping("/call.renegotiate")
    public void renegotiate(CallRenegotiateOffer inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String fromUserId = principal.getName();
            String toUserId = call.otherParty(fromUserId);
            CallRenegotiateOffer outbound = new CallRenegotiateOffer(inbound.callId(), fromUserId, inbound.sdpOffer());
            messagingTemplate.convertAndSendToUser(toUserId, "/queue/calls", outbound, headersFor("renegotiate-offer"));
        });
    }

    /** The non-offering side's answer to a call.renegotiate above — same CallAnswer shape as the initial answer, just a different relay header so the client routes it to the right handler. */
    @MessageMapping("/call.renegotiate-answer")
    public void renegotiateAnswer(CallAnswer inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String fromUserId = principal.getName();
            String toUserId = call.otherParty(fromUserId);
            CallAnswer outbound = new CallAnswer(inbound.callId(), fromUserId, inbound.sdpAnswer(), inbound.fromDeviceId());
            messagingTemplate.convertAndSendToUser(toUserId, "/queue/calls", outbound, headersFor("renegotiate-answer"));
        });
    }

    /**
     * Fire-and-forget — sent by whichever side notices the call ending,
     * regardless of who hung up (see mobile's resetCallState), so BOTH
     * parties' own bytesSent/bytesReceived reach the call log independently
     * rather than only whoever happened to send call.end getting recorded.
     */
    @MessageMapping("/call.report-usage")
    public void reportUsage(CallUsageReport inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String userId = principal.getName();
            if (userId.equals(call.getCallerId())) {
                call.setCallerBytesSent(inbound.bytesSent());
                call.setCallerBytesReceived(inbound.bytesReceived());
                callRepository.save(call);
            } else if (userId.equals(call.getCalleeId())) {
                call.setCalleeBytesSent(inbound.bytesSent());
                call.setCalleeBytesReceived(inbound.bytesReceived());
                callRepository.save(call);
            }
        });
    }

    @MessageMapping("/call.end")
    public void end(CallEnd inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String fromUserId = principal.getName();
            if (call.getStatus() == Call.CallStatus.RINGING) {
                if ("busy".equals(inbound.reason())) {
                    call.setStatus(Call.CallStatus.BUSY);
                } else if ("declined".equals(inbound.reason())) {
                    call.setStatus(Call.CallStatus.DECLINED);
                } else {
                    call.setStatus(Call.CallStatus.MISSED);
                }
            } else {
                call.setStatus(Call.CallStatus.ENDED);
            }
            call.setEndedAt(Instant.now());
            callRepository.save(call);

            String toUserId = call.otherParty(fromUserId);
            CallEnd outbound = new CallEnd(inbound.callId(), fromUserId, inbound.reason(), inbound.fromDeviceId());
            messagingTemplate.convertAndSendToUser(toUserId, "/queue/calls", outbound, headersFor("end"));

            callLogService.logCallAsMessage(call);
        });
    }

    private Map<String, Object> headersFor(String callMessageType) {
        return Map.of("callMessageType", callMessageType);
    }
}
