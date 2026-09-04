package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunResult;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunSpec;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.NetworkMode;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ResourceLimits;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.application.port.out.SecretResolver;
import com.qualityops.worker.execution.application.service.ReportParserRegistry;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.RepoExecutionMetadata;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.execution.exception.ContainerRunException;
import com.qualityops.worker.execution.exception.DigestMismatchException;
import com.qualityops.worker.execution.exception.ImageNotAllowlistedException;
import com.qualityops.worker.execution.exception.ReportParseException;
import com.qualityops.worker.execution.exception.SecretNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ADR-009 §1/§6/§7/§8/§9/§10 — orchestrates one repository-run case as two
 * hardened sibling containers (checkout, then framework) via
 * {@link ContainerRunnerPort}. Never runs repository code in-JVM; never throws
 * for a test/timeout/blocked outcome — those are encoded in the returned
 * {@link CaseExecutionResult}.
 */
@Component
@ConditionalOnProperty(name = "qualityops.repo-exec.enabled", havingValue = "true", matchIfMissing = true)
public class RepositoryExecutionRunner implements ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(RepositoryExecutionRunner.class);
    private static final Pattern SHA_40 = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final String LABEL_RUN = "com.qualityops.run.id";

    private final ContainerRunnerPort containerRunner;
    private final RepoExecWorkerProperties props;
    private final SecretResolver secretResolver;
    private final Redactor redactor;
    private final ReportParserRegistry reportParsers;
    private final WorkspacePathResolver pathResolver;
    private final RepoExecMetrics metrics;

    public RepositoryExecutionRunner(ContainerRunnerPort containerRunner, RepoExecWorkerProperties props,
                                     SecretResolver secretResolver, Redactor redactor,
                                     ReportParserRegistry reportParsers, WorkspacePathResolver pathResolver,
                                     RepoExecMetrics metrics) {
        this.containerRunner = containerRunner;
        this.props = props;
        this.secretResolver = secretResolver;
        this.redactor = redactor;
        this.reportParsers = reportParsers;
        this.pathResolver = pathResolver;
        this.metrics = metrics;
    }

    @Override
    public RunnerKind kind() {
        return RunnerKind.REPOSITORY;
    }

    @Override
    public CaseExecutionResult execute(CaseExecutionContext ctx) {
        var c = ctx.testCase();
        RepoTestSnapshot repo = c.repoTest();
        if (repo == null) {
            throw new com.qualityops.worker.execution.exception.ExecutionHarnessException(
                "RepositoryExecutionRunner invoked for a non-repository case " + c.testCaseId());
        }
        Instant caseStart = Instant.now();

        // 1. resolve secrets — an unresolvable key is a deterministic config
        // problem, never retried (ADR-005 §4.4 precedent).
        Map<String, String> secretEnv = new LinkedHashMap<>();
        for (var sv : nullSafe(repo.secretVars())) {
            try {
                secretEnv.put(sv.name(), secretResolver.resolve(sv.ref().key()));
            } catch (SecretNotFoundException e) {
                metrics.blocked("secret_unresolved");
                return blockedResult(c, ctx, caseStart, "unresolved secret reference: " + sv.ref().key());
            }
        }
        String checkoutToken = null;
        if (repo.credentialRef() != null && !repo.credentialRef().isBlank()) {
            try {
                checkoutToken = secretResolver.resolve(repo.credentialRef());
            } catch (SecretNotFoundException e) {
                metrics.blocked("secret_unresolved");
                return blockedResult(c, ctx, caseStart, "unresolved secret reference: " + repo.credentialRef());
            }
        }

        // 2. per-execution redaction mask: resolved secret plaintexts + the
        // checkout token, on top of the existing regex-based rules (ADR §8).
        var maskLiterals = new LinkedHashSet<String>(secretEnv.values());
        if (checkoutToken != null) {
            maskLiterals.add(checkoutToken);
        }
        Redactor.RedactionView redaction = redactor.forExecution(maskLiterals);

        if (!isSpecValid(repo)) {
            metrics.blocked("spec_invalid");
            return blockedResult(c, ctx, caseStart, "invalid repository test specification");
        }

        // 3. per-attempt workspace directory (bind-mount source; DockerContainerRunner
        // also guarantees this defensively, but the runner owns its lifecycle).
        Path workspaceDir = props.workspaceRootPath().resolve(ctx.executionId().toString())
            .resolve(String.valueOf(ctx.attemptEpoch()));
        try {
            Files.createDirectories(workspaceDir);
        } catch (IOException e) {
            return errorResult(c, ctx, caseStart, "could not create workspace directory",
                SideEffectClass.NONE_OBSERVED, repo, null, List.of(), List.of());
        }

        List<String> consoleLines = Collections.synchronizedList(new ArrayList<>());
        ContainerRunnerPort.LogSink sink = line -> consoleLines.add(redaction.line(line));
        String imageDigest = digestOf(repo.runnerImageRef());

        try {
            return runPhases(c, ctx, repo, workspaceDir, secretEnv, checkoutToken, sink, consoleLines,
                redaction, caseStart, imageDigest);
        } finally {
            containerRunner.cleanup(ctx.executionId());
        }
    }

    private CaseExecutionResult runPhases(com.qualityops.events.TestCaseSnapshotItem c, CaseExecutionContext ctx,
                                          RepoTestSnapshot repo, Path workspaceDir, Map<String, String> secretEnv,
                                          String checkoutToken, ContainerRunnerPort.LogSink sink,
                                          List<String> consoleLines, Redactor.RedactionView redaction,
                                          Instant caseStart, String imageDigest) {
        // 4. checkout container — SideEffectClass stays NONE_OBSERVED throughout.
        ContainerRunResult checkoutResult;
        try {
            checkoutResult = containerRunner.run(checkoutSpec(ctx, repo, workspaceDir, checkoutToken),
                sink, ctx.cancellation());
        } catch (ImageNotAllowlistedException e) {
            metrics.blocked("image_not_allowlisted");
            return blockedResult(c, ctx, caseStart, "checkout image is not on the allowlist");
        } catch (DigestMismatchException e) {
            metrics.blocked("digest_mismatch");
            return blockedResult(c, ctx, caseStart, "checkout image digest mismatch");
        } catch (ContainerRunException e) {
            metrics.run(repo.framework().name(), "error");
            return errorResult(c, ctx, caseStart, "checkout container failed to start",
                SideEffectClass.NONE_OBSERVED, repo, null, consoleLines, List.of());
        }
        Instant checkoutAt = checkoutResult.finishedAt();
        if (checkoutResult.cancelled()) {
            return finish(c, ctx, caseStart, CaseStatus.ERROR, "run cancelled", SideEffectClass.NONE_OBSERVED,
                List.of(), repo, workspaceDir, consoleLines, checkoutAt, null, null, null, imageDigest);
        }
        if (checkoutResult.timedOut() || checkoutResult.exitCode() != 0) {
            metrics.run(repo.framework().name(), checkoutResult.timedOut() ? "timeout" : "error");
            return finish(c, ctx, caseStart,
                checkoutResult.timedOut() ? CaseStatus.TIMEOUT : CaseStatus.ERROR,
                "checkout failed (exit " + checkoutResult.exitCode() + ")", SideEffectClass.NONE_OBSERVED,
                List.of(), repo, workspaceDir, consoleLines, checkoutAt, null, null,
                checkoutResult.exitCode(), imageDigest);
        }

        // 5. framework container — argv exec-form, never `sh -c`. Once run() is
        // invoked (the container has started), the side effect flips to POSSIBLE
        // (gap #5): the test command may have touched external systems.
        SideEffectClass sideEffect;
        ContainerRunResult frameworkResult;
        try {
            frameworkResult = containerRunner.run(frameworkSpec(ctx, repo, workspaceDir, secretEnv),
                sink, ctx.cancellation());
            sideEffect = SideEffectClass.POSSIBLE;
        } catch (ImageNotAllowlistedException e) {
            metrics.blocked("image_not_allowlisted");
            return blockedResult(c, ctx, caseStart, "framework image is not on the allowlist");
        } catch (DigestMismatchException e) {
            metrics.blocked("digest_mismatch");
            return blockedResult(c, ctx, caseStart, "framework image digest mismatch");
        } catch (ContainerRunException e) {
            metrics.run(repo.framework().name(), "error");
            return finish(c, ctx, caseStart, CaseStatus.ERROR, "framework container failed to start",
                SideEffectClass.NONE_OBSERVED, List.of(), repo, workspaceDir, consoleLines, checkoutAt,
                null, null, null, imageDigest);
        }
        if (frameworkResult.cancelled()) {
            return finish(c, ctx, caseStart, CaseStatus.ERROR, "run cancelled", sideEffect, List.of(), repo,
                workspaceDir, consoleLines, checkoutAt, frameworkResult.startedAt(), frameworkResult.finishedAt(),
                frameworkResult.exitCode(), imageDigest);
        }

        // 7. report parsing — a malformed report ⇒ case ERROR, run not aborted.
        List<Path> reportFiles = pathResolver.resolve(workspaceDir, nullSafe(repo.reportPaths()));
        List<RepositoryTestItem> items;
        try {
            items = reportParsers.get(repo.reportFormat()).parse(reportFiles);
            metrics.reportParse(repo.reportFormat().name(), "ok", Duration.ZERO);
        } catch (ReportParseException e) {
            metrics.reportParse(repo.reportFormat().name(), "error", Duration.ZERO);
            CaseStatus status = frameworkResult.timedOut() ? CaseStatus.TIMEOUT : CaseStatus.ERROR;
            metrics.run(repo.framework().name(), status.name().toLowerCase(Locale.ROOT));
            return finish(c, ctx, caseStart, status, redaction.line("report parse failed: " + safeMessage(e)),
                sideEffect, List.of(), repo, workspaceDir, consoleLines, checkoutAt, frameworkResult.startedAt(),
                frameworkResult.finishedAt(), frameworkResult.exitCode(), imageDigest);
        }
        items = items.stream().map(i -> redactItem(i, redaction)).toList();
        items.forEach(i -> metrics.item(i.status().name().toLowerCase(Locale.ROOT)));

        // 8. run-level status: PASSED iff exit 0 AND zero FAILED/ERROR items.
        boolean anyFailedOrError = items.stream()
            .anyMatch(i -> i.status() == RepoItemStatus.FAILED || i.status() == RepoItemStatus.ERROR);
        CaseStatus status;
        String reason;
        if (frameworkResult.timedOut()) {
            status = CaseStatus.TIMEOUT;
            reason = "repository run exceeded its timeout";
        } else if (frameworkResult.exitCode() == 0 && !anyFailedOrError) {
            status = CaseStatus.PASSED;
            reason = null;
        } else {
            status = CaseStatus.FAILED;
            long failed = items.stream()
                .filter(i -> i.status() == RepoItemStatus.FAILED || i.status() == RepoItemStatus.ERROR).count();
            reason = redaction.line(failed + " of " + items.size() + " tests failed; exit "
                + frameworkResult.exitCode());
        }
        metrics.run(repo.framework().name(), status.name().toLowerCase(Locale.ROOT));

        return finish(c, ctx, caseStart, status, reason, sideEffect, items, repo, workspaceDir, consoleLines,
            checkoutAt, frameworkResult.startedAt(), frameworkResult.finishedAt(), frameworkResult.exitCode(),
            imageDigest);
    }

    // --- container spec builders ---

    private ContainerRunSpec checkoutSpec(CaseExecutionContext ctx, RepoTestSnapshot repo, Path workspaceDir,
                                          String checkoutToken) {
        Map<String, String> env = checkoutToken == null ? Map.of() : Map.of("CHECKOUT_TOKEN", checkoutToken);
        return new ContainerRunSpec(ctx.executionId(), ctx.attemptEpoch(), "checkout",
            props.images().checkout(), List.of("sh", "-c"), List.of(checkoutScript(repo, checkoutToken != null)),
            "/workspace", env, workspaceDir, resourceLimits(repo), NetworkMode.EGRESS, ctx.effectiveTimeout(),
            Map.of(LABEL_RUN, ctx.runId().toString()));
    }

    private ContainerRunSpec frameworkSpec(CaseExecutionContext ctx, RepoTestSnapshot repo, Path workspaceDir,
                                           Map<String, String> secretEnv) {
        var env = new LinkedHashMap<String, String>();
        nullSafe(repo.environmentVars()).forEach(v -> env.put(v.name(), v.value()));
        env.putAll(secretEnv);
        env.put("CI", "true");
        env.put("QUALITYOPS_RUN_ID", ctx.runId().toString());
        env.put("QUALITYOPS_COMMIT_SHA", repo.commitSha());
        String workingDir = repo.workingDir() == null || repo.workingDir().isBlank()
            ? "/workspace" : "/workspace/" + trimLeadingSlash(repo.workingDir());
        return new ContainerRunSpec(ctx.executionId(), ctx.attemptEpoch(), "framework", repo.runnerImageRef(),
            null, repo.command(), workingDir, env, workspaceDir, resourceLimits(repo),
            networkModeOf(repo.networkPolicy()), ctx.effectiveTimeout(), Map.of(LABEL_RUN, ctx.runId().toString()));
    }

    private ResourceLimits resourceLimits(RepoTestSnapshot repo) {
        var profile = props.profileFor(repo.resourceProfile());
        var container = props.container();
        long memoryBytes = (long) profile.memoryMb() * 1024 * 1024;
        long nanoCpus = (long) profile.cpus() * 1_000_000_000L;
        long tmpfsBytes = (long) container.tmpfsMb() * 1024 * 1024;
        long workspaceBytes = container.maxWorkspaceMb() * 1024 * 1024;
        return new ResourceLimits(memoryBytes, nanoCpus, container.pidsLimit(), tmpfsBytes, workspaceBytes,
            container.nofileSoft(), container.nofileHard());
    }

    private static NetworkMode networkModeOf(RepoNetworkPolicy policy) {
        return policy == RepoNetworkPolicy.EGRESS ? NetworkMode.EGRESS : NetworkMode.NONE;
    }

    /** Platform-controlled checkout entrypoint (ADR §6) — the repo cannot
     *  override it. {@code host}/{@code repoPath}/{@code commitSha} are
     *  regex-validated by {@link #isSpecValid} before this is ever built. */
    private static String checkoutScript(RepoTestSnapshot repo, boolean hasToken) {
        String url = "https://" + repo.repoHost() + "/" + repo.repoPath() + ".git";
        var sb = new StringBuilder("git init /workspace >/dev/null && cd /workspace && git remote add origin '")
            .append(url).append('\'');
        if (hasToken) {
            sb.append(" && printf '#!/bin/sh\\necho \"$CHECKOUT_TOKEN\"\\n' > /tmp/askpass.sh")
                .append(" && chmod +x /tmp/askpass.sh")
                .append(" && export GIT_ASKPASS=/tmp/askpass.sh GIT_TERMINAL_PROMPT=0");
        }
        sb.append(" && git fetch --depth 1 origin ").append(repo.commitSha());
        sb.append(" && git checkout --detach ").append(repo.commitSha());
        sb.append("; rc=$?; rm -f /tmp/askpass.sh; exit $rc");
        return sb.toString();
    }

    private static boolean isSpecValid(RepoTestSnapshot repo) {
        return repo.commitSha() != null && SHA_40.matcher(repo.commitSha()).matches()
            && repo.runnerImageRef() != null && repo.runnerImageRef().contains("@sha256:")
            && repo.command() != null && !repo.command().isEmpty()
            && repo.repoHost() != null && SAFE_SEGMENT.matcher(repo.repoHost()).matches()
            && repo.repoPath() != null && SAFE_SEGMENT.matcher(repo.repoPath()).matches();
    }

    private static String digestOf(String imageRef) {
        int at = imageRef == null ? -1 : imageRef.indexOf('@');
        return at < 0 ? null : imageRef.substring(at + 1);
    }

    private static String trimLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private RepositoryTestItem redactItem(RepositoryTestItem item, Redactor.RedactionView redaction) {
        String message = item.failureMessage();
        if (message != null) {
            message = redaction.line(message);
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            int cap = props.maxItemMessageBytes();
            if (cap > 0 && bytes.length > cap) {
                message = new String(bytes, 0, cap, StandardCharsets.UTF_8);
            }
        }
        if (!props.persistReportSnippets()) {
            message = null;
        }
        return new RepositoryTestItem(item.suite(), item.name(), item.status(), item.durationMillis(),
            item.failureType(), message);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    // --- result assembly ---

    private CaseExecutionResult blockedResult(com.qualityops.events.TestCaseSnapshotItem c,
                                              CaseExecutionContext ctx, Instant caseStart, String reason) {
        return new CaseExecutionResult(c.testCaseId(), c.name(), c.orderIndex(), CaseStatus.BLOCKED,
            Duration.between(caseStart, Instant.now()), null, null, List.of(), reason, null,
            SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch(), null);
    }

    private CaseExecutionResult errorResult(com.qualityops.events.TestCaseSnapshotItem c,
                                            CaseExecutionContext ctx, Instant caseStart, String reason,
                                            SideEffectClass sideEffect, RepoTestSnapshot repo, Instant checkoutAt,
                                            List<String> consoleLines, List<RepositoryTestItem> items) {
        return finish(c, ctx, caseStart, CaseStatus.ERROR, reason, sideEffect, items, repo, null, consoleLines,
            checkoutAt, null, null, null, null);
    }

    /** Stages the console log + resolved report/artifact files to stable temp
     *  paths (survives the {@code containerRunner.cleanup()} that deletes the
     *  workspace dir), builds the provenance, and returns the final result. */
    private CaseExecutionResult finish(com.qualityops.events.TestCaseSnapshotItem c, CaseExecutionContext ctx,
                                       Instant caseStart, CaseStatus status, String reason,
                                       SideEffectClass sideEffect, List<RepositoryTestItem> items,
                                       RepoTestSnapshot repo, Path workspaceDir, List<String> consoleLines,
                                       Instant checkoutAt, Instant startedAt, Instant finishedAt, Integer exitCode,
                                       String imageDigest) {
        String consoleLogTempPath = null;
        long consoleLogBytes = 0;
        if (consoleLines != null && !consoleLines.isEmpty()) {
            try {
                Path staged = Files.createTempFile("qo-repo-console-", ".log");
                Files.writeString(staged, String.join("\n", consoleLines), StandardCharsets.UTF_8);
                consoleLogTempPath = staged.toString();
                consoleLogBytes = Files.size(staged);
            } catch (IOException e) {
                log.warn("could not stage console log for execution {}: {}", ctx.executionId(), e.toString());
            }
        }

        var reportCaptures = new ArrayList<RepoExecutionMetadata.ReportCapture>();
        if (repo != null && workspaceDir != null && Files.isDirectory(workspaceDir)) {
            var globs = new ArrayList<String>();
            globs.addAll(nullSafe(repo.reportPaths()));
            globs.addAll(nullSafe(repo.artifactGlobs()));
            long budget = props.maxReportBytes();
            long used = 0;
            for (Path f : pathResolver.resolve(workspaceDir, globs)) {
                try {
                    long size = Files.size(f);
                    if (used + size > budget) {
                        continue;
                    }
                    Path staged = Files.createTempFile("qo-repo-report-", extensionOf(f));
                    Files.copy(f, staged, StandardCopyOption.REPLACE_EXISTING);
                    String relativeName = workspaceDir.relativize(f).toString().replace('\\', '/');
                    reportCaptures.add(new RepoExecutionMetadata.ReportCapture(staged.toString(), size,
                        relativeName));
                    used += size;
                } catch (IOException e) {
                    log.debug("could not stage report/artifact file {}: {}", f, e.toString());
                }
            }
        }

        RepositoryRunProvenance provenance = repo == null ? null : new RepositoryRunProvenance(
            imageDigest, exitCode, items.size(),
            (int) items.stream().filter(i -> i.status() == RepoItemStatus.PASSED).count(),
            (int) items.stream().filter(i -> i.status() == RepoItemStatus.FAILED
                || i.status() == RepoItemStatus.ERROR).count(),
            (int) items.stream().filter(i -> i.status() == RepoItemStatus.SKIPPED).count(),
            checkoutAt, startedAt, finishedAt);

        var metadata = new RepoExecutionMetadata(items, provenance, consoleLogTempPath, consoleLogBytes,
            List.copyOf(reportCaptures));

        return new CaseExecutionResult(c.testCaseId(), c.name(), c.orderIndex(), status,
            Duration.between(caseStart, Instant.now()), null, null, List.of(), reason, null,
            sideEffect, ctx.attemptEpoch(), metadata);
    }

    private static String extensionOf(Path f) {
        String name = f.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
