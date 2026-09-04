package com.qualityops.api.realtime.config;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.exception.RunNotFoundException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates the STOMP {@code CONNECT} (JWT bearer, mirroring
 * {@code JwtAuthenticationFilter}) and authorises every {@code SUBSCRIBE} to a
 * run topic against the caller's org (ADR-008 §5). Any rejection is raised as a
 * {@link MessagingException}, which Spring turns into a STOMP {@code ERROR} frame
 * and closes the session.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final Pattern RUN_TOPIC = Pattern.compile("^/topic/runs/([0-9a-fA-F-]{36})$");

    private final JwtService jwtService;
    private final GetRunUseCase getRunUseCase;

    public StompAuthChannelInterceptor(JwtService jwtService, GetRunUseCase getRunUseCase) {
        this.jwtService = jwtService;
        this.getRunUseCase = getRunUseCase;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        try {
            String token = bearerToken(accessor.getFirstNativeHeader("Authorization"));
            Claims claims = jwtService.validateAndParseToken(token);
            var principal = new UserPrincipal(
                jwtService.extractUserId(claims),
                jwtService.extractOrgId(claims),
                jwtService.extractRole(claims));
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("STOMP CONNECT rejected — missing or invalid bearer token", e);
            throw new MessagingException("unauthenticated");
        }
    }

    /**
     * The socket's tenant isolation boundary: a client may only subscribe to a
     * run that {@link GetRunUseCase#get} resolves within the principal's org. A
     * wrong-org or unknown runId throws {@link RunNotFoundException}, which we
     * translate into a denied subscription.
     */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        UserPrincipal principal = principal(accessor);
        UUID runId = parseRunId(accessor.getDestination());
        try {
            getRunUseCase.get(runId, principal.orgId());
        } catch (RunNotFoundException e) {
            log.debug("STOMP SUBSCRIBE denied — run {} not visible to org {}", runId, principal.orgId());
            throw new MessagingException("forbidden");
        }
    }

    private static String bearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new IllegalArgumentException("missing bearer token");
        }
        return header.substring("Bearer ".length());
    }

    private static UserPrincipal principal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth
            && auth.getPrincipal() instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        throw new MessagingException("unauthenticated");
    }

    private static UUID parseRunId(String destination) {
        if (destination == null) {
            throw new MessagingException("forbidden");
        }
        Matcher matcher = RUN_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            throw new MessagingException("forbidden");
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            throw new MessagingException("forbidden");
        }
    }
}
