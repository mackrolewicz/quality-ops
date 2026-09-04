package com.qualityops.api.audit.aspect;

import com.qualityops.api.audit.application.AuditRecorder;
import com.qualityops.api.audit.domain.AuditOutcome;
import com.qualityops.api.audit.support.AopTestFixtures;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.identity.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ADR-008 §7 — documents the AOP self-invocation limitation as executable
 * behaviour: a call entering the bean through the proxy is audited; a
 * {@code this.inner()} call from a sibling method is not.
 */
@SpringBootTest(classes = AopSelfInvocationTest.Config.class)
class AopSelfInvocationTest {

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(AopTestFixtures.class)
    static class Config {
        @Bean
        AuditAspect auditAspect(AuditRecorder recorder) {
            return new AuditAspect(recorder, new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }

    @MockBean private AuditRecorder recorder;
    @Autowired private AopTestFixtures.SelfInvocationFixtureBean fixture;

    @BeforeEach
    void authenticate() {
        var principal = new UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void annotatedMethod_calledThroughProxy_aspectFires() {
        UUID id = UUID.randomUUID();

        fixture.inner(id);

        verify(recorder, times(1)).record(any(), any(), eq("test.self"), eq("thing"),
            eq(id), eq(AuditOutcome.SUCCESS), any());
    }

    @Test
    void annotatedMethod_calledViaThisFromSibling_aspectDoesNotFire() {
        fixture.outer(UUID.randomUUID());

        verify(recorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
