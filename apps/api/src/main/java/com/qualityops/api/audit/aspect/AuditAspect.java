package com.qualityops.api.audit.aspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.audit.annotation.Audited;
import com.qualityops.api.audit.application.AuditRecorder;
import com.qualityops.api.audit.domain.AuditOutcome;
import com.qualityops.api.config.UserPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an {@code audit_log} row for every {@link Audited} call (ADR-008
 * &sect;7). {@code @Order(10)} &mdash; inner to {@link TimingAspect}. On a thrown
 * exception, records {@link AuditOutcome#FAILURE} with the exception class +
 * message then rethrows unchanged.
 *
 * <p>{@code targetId} resolution: the return value's {@code id()} /
 * {@code environmentId()} UUID accessor first (so {@code create}/{@code register}
 * report the new resource), else the first {@code UUID} argument (so
 * {@code update}/{@code delete}/{@code set} report their subject), else null.
 * This refines the plan's arg-first heuristic, which is wrong for {@code create}.
 */
@Aspect
@Component
@Order(10)
@ConditionalOnProperty(name = "qualityops.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final List<String> ID_ACCESSORS = List.of("id", "environmentId");

    private final AuditRecorder recorder;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        UserPrincipal principal = currentPrincipalOrNull();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            safeRecord(principal, audited, targetId(pjp, null), AuditOutcome.FAILURE, errorDetailJson(t));
            throw t;
        }
        safeRecord(principal, audited, targetId(pjp, result), AuditOutcome.SUCCESS, null);
        return result;
    }

    private void safeRecord(UserPrincipal principal, Audited audited, UUID targetId,
                            AuditOutcome outcome, String detailJson) {
        if (principal == null || principal.orgId() == null) {
            log.debug("skipping @Audited {} - no authenticated principal / orgId", audited.action());
            return;
        }
        try {
            recorder.record(principal.orgId(), principal.userId(), audited.action(),
                audited.targetType(), targetId, outcome, detailJson);
        } catch (RuntimeException e) {
            log.warn("audit record dispatch failed action={}", audited.action(), e);
        }
    }

    private static UUID targetId(ProceedingJoinPoint pjp, Object result) {
        UUID fromResult = idFromResult(result);
        if (fromResult != null) {
            return fromResult;
        }
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof UUID uuid) {
                return uuid;
            }
        }
        return null;
    }

    private static UUID idFromResult(Object result) {
        if (result == null) {
            return null;
        }
        for (String accessor : ID_ACCESSORS) {
            try {
                Method method = result.getClass().getMethod(accessor);
                if (method.getReturnType() == UUID.class) {
                    return (UUID) method.invoke(result);
                }
            } catch (ReflectiveOperationException ignored) {
                // try the next accessor
            }
        }
        return null;
    }

    /**
     * Serialises the failure detail as valid JSON via Jackson (never hand-rolled —
     * an exception message with a control char would otherwise produce invalid
     * {@code jsonb} and the whole FAILURE row would be lost). Falls back to a
     * null detail rather than dropping the audit row if serialisation itself fails.
     */
    private String errorDetailJson(Throwable t) {
        String message = t.getMessage();
        String detail = message == null ? t.getClass().getSimpleName()
            : t.getClass().getSimpleName() + ": " + message;
        try {
            return objectMapper.writeValueAsString(Map.of("error", detail));
        } catch (JsonProcessingException e) {
            log.warn("could not serialise audit failure detail", e);
            return null;
        }
    }

    private static UserPrincipal currentPrincipalOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        return null;
    }
}
