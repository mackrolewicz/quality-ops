package com.qualityops.worker.config;

import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoResourceProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** ADR-009 §12 — the Worker-side {@code qualityops.repo-exec.*} block: the
 *  digest-pinned image allowlist (incl. {@code checkout}), the Docker endpoint,
 *  the container-hardening knobs, per-profile resource envelopes, and the
 *  workspace / report / secret settings. Auto-registered by
 *  {@code @ConfigurationPropertiesScan}. */
@ConfigurationProperties("qualityops.repo-exec")
public record RepoExecWorkerProperties(
    boolean enabled,
    Images images,
    boolean imagePullOnStartup,
    Duration defaultRunTimeout,
    Duration maxRunTimeout,
    RepoResourceProfile defaultResourceProfile,
    Docker docker,
    Container container,
    Network network,
    Map<String, Profile> resourceProfiles,
    String workspaceRoot,
    long maxReportBytes,
    int maxItemMessageBytes,
    boolean persistReportSnippets,
    boolean uploadSecretRunArtifacts,
    Duration cancelPoll,
    Duration hardKillGrace,
    Duration containerSweepInterval,
    String secretEnvPrefix
) {
    /** Digest-pinned refs — one per {@link FrameworkPreset} plus the checkout image. */
    public record Images(String playwright, String junit, String pytest, String cypress, String k6,
                         String checkout) {

        public String forPreset(FrameworkPreset preset) {
            return switch (preset) {
                case PLAYWRIGHT -> playwright;
                case JUNIT -> junit;
                case PYTEST -> pytest;
                case CYPRESS -> cypress;
                case K6 -> k6;
            };
        }

        /** All 6 allowlist entries, non-blank. */
        public java.util.List<String> all() {
            return java.util.stream.Stream.of(playwright, junit, pytest, cypress, k6, checkout)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        }
    }

    public record Docker(String host, boolean requireProxy) {}

    public record Container(
        int runnerUid, int runnerGid, int pidsLimit, int tmpfsMb, long maxWorkspaceMb,
        Duration workspaceWatchdog, int nofileSoft, int nofileHard) {}

    public record Network(String isolatedMode, String egressNetwork) {}

    public record Profile(int cpus, int memoryMb) {}

    public Profile profileFor(RepoResourceProfile p) {
        var key = (p == null ? defaultResourceProfile() : p).name().toLowerCase(java.util.Locale.ROOT);
        var profile = resourceProfiles == null ? null : resourceProfiles.get(key);
        return profile != null ? profile : new Profile(1, 1024);
    }

    public java.nio.file.Path workspaceRootPath() {
        return java.nio.file.Path.of(workspaceRoot);
    }
}
