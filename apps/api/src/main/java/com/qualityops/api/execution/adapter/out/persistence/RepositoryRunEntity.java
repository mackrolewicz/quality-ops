package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepositoryProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** ADR-009 §3 (V24) — read-path mapping only. Writes go through native guarded
 *  statements in {@link RepositoryRunJpaRepository}. All enum-like columns are
 *  {@code VARCHAR + CHECK}, mapped as {@code @Enumerated(STRING)}. */
@Entity
@Table(name = "repository_run")
class RepositoryRunEntity {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private RepositoryProvider provider;

    @Column(name = "repo_host", nullable = false)
    private String repoHost;

    @Column(name = "repo_path", nullable = false)
    private String repoPath;

    @Column(name = "requested_ref", nullable = false)
    private String requestedRef;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false)
    private RepoRefType refType;

    @Enumerated(EnumType.STRING)
    @Column(name = "framework_preset", nullable = false)
    private FrameworkPreset frameworkPreset;

    @Column(name = "runner_image_ref", nullable = false)
    private String runnerImageRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_profile", nullable = false)
    private RepoResourceProfile resourceProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_policy", nullable = false)
    private RepoNetworkPolicy networkPolicy;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private RepositoryRunState state;

    @Column(name = "runner_image_digest")
    private String runnerImageDigest;

    @Column(name = "container_exit_code")
    private Integer containerExitCode;

    @Column(name = "items_total")
    private Integer itemsTotal;

    @Column(name = "items_passed")
    private Integer itemsPassed;

    @Column(name = "items_failed")
    private Integer itemsFailed;

    @Column(name = "items_skipped")
    private Integer itemsSkipped;

    @Column(name = "checkout_at")
    private Instant checkoutAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_detail")
    private String errorDetail;

    protected RepositoryRunEntity() {}

    UUID getRunId() {
        return runId;
    }

    UUID getOrgId() {
        return orgId;
    }

    RepositoryProvider getProvider() {
        return provider;
    }

    String getRepoHost() {
        return repoHost;
    }

    String getRepoPath() {
        return repoPath;
    }

    String getRequestedRef() {
        return requestedRef;
    }

    String getCommitSha() {
        return commitSha;
    }

    RepoRefType getRefType() {
        return refType;
    }

    FrameworkPreset getFrameworkPreset() {
        return frameworkPreset;
    }

    String getRunnerImageRef() {
        return runnerImageRef;
    }

    RepoResourceProfile getResourceProfile() {
        return resourceProfile;
    }

    RepoNetworkPolicy getNetworkPolicy() {
        return networkPolicy;
    }

    int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    RepositoryRunState getState() {
        return state;
    }

    String getRunnerImageDigest() {
        return runnerImageDigest;
    }

    Integer getContainerExitCode() {
        return containerExitCode;
    }

    Integer getItemsTotal() {
        return itemsTotal;
    }

    Integer getItemsPassed() {
        return itemsPassed;
    }

    Integer getItemsFailed() {
        return itemsFailed;
    }

    Integer getItemsSkipped() {
        return itemsSkipped;
    }

    Instant getCheckoutAt() {
        return checkoutAt;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getFinishedAt() {
        return finishedAt;
    }

    String getErrorDetail() {
        return errorDetail;
    }
}
