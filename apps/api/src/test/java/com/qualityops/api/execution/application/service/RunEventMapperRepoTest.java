package com.qualityops.api.execution.application.service;

import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolvedRepositoryRun;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 §4 — {@code RunEventMapper} repository mapping + defence-in-depth. */
class RunEventMapperRepoTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String IMAGE =
        "python:3.12-slim@sha256:1111111111111111111111111111111111111111111111111111111111111111";

    private final RunEventMapper mapper = new RunEventMapper();
    private final UUID connectionId = UUID.randomUUID();

    private com.qualityops.api.testsuite.domain.RepoTestSpec authoredSuiteSpec() {
        return new com.qualityops.api.testsuite.domain.RepoTestSpec(connectionId, "main", "PYTEST", "svc",
            List.of("pytest", "--junitxml=report.xml"), "JUNIT_XML", List.of("report.xml"),
            List.of("artifacts/**"),
            List.of(new com.qualityops.api.testsuite.domain.RepoTestSpec.EnvVarSpec("CI_NAME", "qo")),
            List.of(new com.qualityops.api.testsuite.domain.RepoTestSpec.SecretVarSpec("TOKEN", "REG_PAT")),
            "MEDIUM", "EGRESS", 900);
    }

    private RepoTestSnapshot snapshot(String sha, String image, List<String> command) {
        return new RepoTestSnapshot(connectionId, RepositoryProvider.GITHUB, "github.com", "acme/web",
            "main", sha, RepoRefType.BRANCH, FrameworkPreset.PYTEST, image, "svc", command,
            RepoReportFormat.JUNIT_XML, List.of("report.xml"), List.of("artifacts/**"),
            List.of(), List.of(), "REG_PAT", RepoResourceProfile.MEDIUM, RepoNetworkPolicy.EGRESS, 900);
    }

    private ResolvedRepositoryRun resolved(RepoTestSnapshot s) {
        var frozen = RepositoryRunFrozen.fromSnapshot(s);
        return new ResolvedRepositoryRun(s, frozen);
    }

    @Test
    void toRepoSpec_copiesEveryAuthoredField() {
        var exec = mapper.toRepoSpec(authoredSuiteSpec());

        assertThat(exec).isNotNull();
        assertThat(exec.repositoryConnectionId()).isEqualTo(connectionId);
        assertThat(exec.framework()).isEqualTo("PYTEST");
        assertThat(exec.command()).containsExactly("pytest", "--junitxml=report.xml");
        assertThat(exec.secretVars()).singleElement()
            .satisfies(v -> assertThat(v.secretRef()).isEqualTo("REG_PAT"));
        assertThat(exec.networkPolicy()).isEqualTo("EGRESS");
        assertThat(exec.timeoutSeconds()).isEqualTo(900);
    }

    @Test
    void toRepoSpec_null_returnsNull() {
        assertThat(mapper.toRepoSpec(null)).isNull();
    }

    @Test
    void toWireRepo_validPreflight_returnsFrozenSnapshotWithDigestPinnedImage() {
        var exec = mapper.toRepoSpec(authoredSuiteSpec());

        var wire = mapper.toWireRepo(exec, resolved(snapshot(SHA, IMAGE, List.of("pytest"))));

        assertThat(wire.commitSha()).isEqualTo(SHA);
        assertThat(wire.runnerImageRef()).isEqualTo(IMAGE).contains("@sha256:");
    }

    @Test
    void toWireRepo_commitShaNot40Hex_throwsIllegalState() {
        var exec = mapper.toRepoSpec(authoredSuiteSpec());

        assertThatThrownBy(() -> mapper.toWireRepo(exec, resolved(snapshot("deadbeef", IMAGE, List.of("x")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("40-hex");
    }

    @Test
    void toWireRepo_imageNotDigestPinned_throwsIllegalState() {
        var exec = mapper.toRepoSpec(authoredSuiteSpec());

        assertThatThrownBy(() ->
            mapper.toWireRepo(exec, resolved(snapshot(SHA, "python:3.12-slim", List.of("x")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("digest-pinned");
    }

    @Test
    void toWireRepo_emptyCommand_throwsIllegalState() {
        var exec = mapper.toRepoSpec(authoredSuiteSpec());

        assertThatThrownBy(() -> mapper.toWireRepo(exec, resolved(snapshot(SHA, IMAGE, List.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("argv");
    }
}
