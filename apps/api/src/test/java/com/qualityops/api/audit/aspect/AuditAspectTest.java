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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** ADR-008 §7 — {@code AuditAspect}: SUCCESS on return, FAILURE + rethrow on throw, no-op when unannotated. */
@SpringBootTest(classes = AuditAspectTest.Config.class)
class AuditAspectTest {

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
    @Autowired private AopTestFixtures.AuditFixtureBean fixture;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        var principal = new UserPrincipal(userId, orgId, Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void annotatedMethod_returnsNormally_recordsOneSuccess() {
        UUID id = UUID.randomUUID();

        var result = fixture.annotated(id);

        assertThat(result.id()).isEqualTo(id);
        verify(recorder, times(1)).record(eq(orgId), eq(userId), eq("test.action"),
            eq("thing"), eq(id), eq(AuditOutcome.SUCCESS), isNull());
    }

    @Test
    void annotatedMethod_throws_recordsOneFailure_andRethrows() {
        assertThatThrownBy(() -> fixture.throwing())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        verify(recorder, times(1)).record(eq(orgId), eq(userId), eq("test.fail"),
            eq("thing"), isNull(), eq(AuditOutcome.FAILURE), contains("IllegalStateException"));
    }

    @Test
    void unannotatedMethod_recordsNothing() {
        fixture.unannotated();

        verifyNoInteractions(recorder);
    }
}
