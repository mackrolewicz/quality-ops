package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.BrowserStep;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.PlaywrightBrowser;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.BrowserAssertionOutcome;
import com.qualityops.worker.execution.domain.BrowserRunCommand;
import com.qualityops.worker.execution.domain.BrowserRunMetadata;
import com.qualityops.worker.execution.domain.BrowserRunOutcome;
import com.qualityops.worker.execution.domain.BrowserStepOutcome;
import com.qualityops.worker.execution.domain.BrowserStepStatus;
import com.qualityops.worker.execution.application.port.out.SecretResolver;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;
import com.qualityops.worker.execution.exception.SecretNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a declarative Playwright browser scenario for one snapshot case. Never
 *  throws for a step/assertion failure, a blocked target or a scenario timeout —
 *  those are encoded in the {@link CaseExecutionResult}. Throws
 *  {@link ExecutionHarnessException} only for a genuine worker fault. */
@Component
public class BrowserExecutionRunner implements ExecutionRunner {

    private final PlaywrightBrowser driver;
    private final TargetValidator validator;
    private final Redactor redactor;
    private final WorkerExecutionProperties props;
    private final ExecutorService playwrightExecutor;
    private final SecretResolver secretResolver;

    public BrowserExecutionRunner(PlaywrightBrowser driver, TargetValidator validator, Redactor redactor,
                                  WorkerExecutionProperties props,
                                  @Qualifier("playwrightExecutor") ExecutorService playwrightExecutor,
                                  SecretResolver secretResolver) {
        this.driver = driver;
        this.validator = validator;
        this.redactor = redactor;
        this.props = props;
        this.playwrightExecutor = playwrightExecutor;
        this.secretResolver = secretResolver;
    }

    @Override
    public RunnerKind kind() {
        return RunnerKind.BROWSER;
    }

    @Override
    public CaseExecutionResult execute(CaseExecutionContext ctx) throws ExecutionHarnessException {
        long start = System.nanoTime();
        BrowserTestSnapshot spec = ctx.testCase().browserTest();
        if (spec == null) {
            return result(ctx, start, CaseStatus.BLOCKED, "no browser test on this case", null);
        }
        if (!props.browser().enabled()) {
            return result(ctx, start, CaseStatus.BLOCKED, "browser execution disabled", null);
        }
        String blocked = firstBlockedUrl(allUrls(spec));
        if (blocked != null) {
            return result(ctx, start, CaseStatus.BLOCKED, blocked, null, SideEffectClass.NONE_OBSERVED);
        }
        if (ctx.cancellation().isCancelled()) {
            return result(ctx, start, CaseStatus.ERROR, "run cancelled", null, SideEffectClass.NONE_OBSERVED);
        }

        // Pre-flight FILL secretRefs. Unresolvable ⇒ BLOCKED (deterministic — never retried);
        // the scenario is not run. Resolved plaintext is discarded here and re-resolved
        // just-in-time by the driver immediately before fill().
        try {
            preflightSecrets(spec.steps());
        } catch (SecretNotFoundException e) {
            return result(ctx, start, CaseStatus.BLOCKED, e.getMessage(), null, SideEffectClass.NONE_OBSERVED);
        }

        BrowserRunCommand command = buildCommand(ctx, spec);
        CompletableFuture<BrowserRunOutcome> f =
            CompletableFuture.supplyAsync(() -> driver.run(command), playwrightExecutor);
        return awaitOutcome(ctx, start, spec, f);
    }

    /** Blocks up to the scenario budget + hard-kill grace for the driver, mapping every
     *  exit path (success / timeout / cancellation / driver fault / interrupt) to a
     *  terminal {@link CaseExecutionResult}. Force-recycles the shared browser on any
     *  path where it may be left wedged. */
    private CaseExecutionResult awaitOutcome(CaseExecutionContext ctx, long start, BrowserTestSnapshot spec,
                                             CompletableFuture<BrowserRunOutcome> f)
            throws ExecutionHarnessException {
        try {
            long budgetMs = ctx.effectiveTimeout().plus(props.browser().hardKillGrace()).toMillis();
            BrowserRunOutcome outcome = f.get(budgetMs, TimeUnit.MILLISECONDS);
            return map(ctx, start, spec, outcome);
        } catch (TimeoutException e) {
            f.cancel(true);
            driver.forceRecycle();
            // Hard future-timeout: cannot prove zero interactions occurred ⇒ not retry-safe.
            return result(ctx, start, CaseStatus.TIMEOUT,
                "browser scenario exceeded " + ctx.effectiveTimeout().toMillis() + " ms",
                partialMetadata(spec), SideEffectClass.POSSIBLE);
        } catch (CancellationException e) {
            return result(ctx, start, CaseStatus.ERROR, "run cancelled", null, SideEffectClass.NONE_OBSERVED);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SecretNotFoundException snf) {
                // Defensive: a FILL secretRef that slipped past preflightSecrets. Deterministic
                // config problem ⇒ BLOCKED (never retried), consistent with the pre-flight path.
                return result(ctx, start, CaseStatus.BLOCKED, snf.getMessage(), null,
                    SideEffectClass.NONE_OBSERVED);
            }
            driver.forceRecycle();
            // Driver threw before producing an outcome (launch / create fault) — no page interaction.
            return result(ctx, start, CaseStatus.ERROR, "browser unavailable", null,
                SideEffectClass.NONE_OBSERVED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            driver.forceRecycle();
            throw new ExecutionHarnessException("browser execution interrupted", e);
        }
    }

    // ---- SSRF ----

    private List<String> allUrls(BrowserTestSnapshot spec) {
        var urls = new ArrayList<String>();
        urls.add(spec.startUrl());
        if (spec.steps() != null) {
            spec.steps().stream()
                .filter(s -> s.action() == BrowserStep.Action.NAVIGATE)
                .forEach(s -> urls.add(s.value()));
        }
        return urls;
    }

    private String firstBlockedUrl(List<String> urls) {
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                return "missing or blank target URL";
            }
            var stage1 = validator.validateUrl(u);
            if (stage1 instanceof TargetValidator.Blocked b) {
                return b.safeReason();
            }
            String host = ((TargetValidator.Allowed) stage1).uri().getHost();
            final List<InetAddress> resolved;
            try {
                resolved = validator.resolve(host);
            } catch (UnknownHostException e) {
                return "could not resolve host";
            }
            if (validator.validateResolved(host, resolved) instanceof TargetValidator.Blocked b) {
                return b.safeReason();
            }
        }
        return null;
    }

    // ---- command / mapping ----

    private BrowserRunCommand buildCommand(CaseExecutionContext ctx, BrowserTestSnapshot spec) {
        var b = props.browser();
        boolean secretCase = spec.steps() != null
            && spec.steps().stream().anyMatch(s -> s.secretValue() != null);
        // A secret-bearing case forces trace capture off (a trace snapshots DOM + network).
        boolean captureTrace = b.captureTrace() && !secretCase;
        return new BrowserRunCommand(
            ctx.executionId(), ctx.testCase().testCaseId(), ctx.attemptEpoch(),
            spec.startUrl(),
            spec.steps() == null ? List.of() : spec.steps(),
            spec.assertions() == null ? List.of() : spec.assertions(),
            b.effectiveStepTimeout(spec.stepTimeoutMillis()).toMillis(),
            b.effectiveNavigationTimeout(spec.navigationTimeoutMillis()).toMillis(),
            b.launchTimeout().toMillis(),
            ctx.effectiveTimeout().toMillis(),
            b.headless(), captureTrace, b.screenshotOnFailure(),
            b.artifactTempDirPath(), b.artifactMaxBytes(),
            b.blockPrivateSubresources(), props.persistBodySnippets(), secretCase);
    }

    private CaseExecutionResult map(CaseExecutionContext ctx, long start, BrowserTestSnapshot spec,
                                    BrowserRunOutcome outcome) {
        int planned = spec.steps() == null ? 0 : spec.steps().size();
        int executed = (int) outcome.steps().stream()
            .filter(s -> s.status() != BrowserStepStatus.TIMEOUT
                || s.failureReason() == null || !s.failureReason().contains("not executed"))
            .count();
        var meta = new BrowserRunMetadata(outcome.steps(), outcome.assertions(), outcome.finalUrl(),
            planned, executed, pathStr(outcome.screenshot()), outcome.screenshotBytes(),
            pathStr(outcome.trace()), outcome.traceBytes());

        CaseStatus status = switch (outcome.status()) {
            case COMPLETED -> allPassed(outcome) ? CaseStatus.PASSED : CaseStatus.FAILED;
            case TIMED_OUT -> CaseStatus.TIMEOUT;
            case FAULT -> CaseStatus.ERROR;
        };
        String reason = status == CaseStatus.PASSED ? null : firstFailureReason(outcome);
        // Retry-safe only if the TIMEOUT/ERROR happened before any interactive step ran.
        SideEffectClass sideEffect = anyInteractiveStepAttempted(outcome)
            ? SideEffectClass.POSSIBLE : SideEffectClass.NONE_OBSERVED;
        return new CaseExecutionResult(ctx.testCase().testCaseId(), ctx.testCase().name(),
            ctx.testCase().orderIndex(), status, Duration.ofNanos(System.nanoTime() - start),
            null, null, List.of(), reason, meta, sideEffect, ctx.attemptEpoch());
    }

    private static boolean allPassed(BrowserRunOutcome outcome) {
        return outcome.steps().stream().allMatch(s -> s.status() == BrowserStepStatus.PASSED)
            && outcome.assertions().stream().allMatch(BrowserAssertionOutcome::passed);
    }

    private String firstFailureReason(BrowserRunOutcome outcome) {
        if (outcome.status() == BrowserRunOutcome.Status.FAULT) {
            // faultReason is a raw PlaywrightException message and frequently embeds the
            // target URL (with query string) or an internal hostname. redactor.value()
            // only strips configured secret patterns, not URLs — so keep the wire reason
            // generic and reveal the driver detail only when snippet persistence is on.
            String generic = "browser navigation or driver fault";
            return props.persistBodySnippets() && outcome.faultReason() != null
                ? generic + ": " + redactor.value(outcome.faultReason()) : generic;
        }
        var badStep = outcome.steps().stream().filter(s -> s.status() != BrowserStepStatus.PASSED).findFirst();
        if (badStep.isPresent()) {
            BrowserStepOutcome s = badStep.get();
            String base = "step " + s.index() + " " + s.action() + " on " + s.selectorDescription()
                + " " + s.status();
            return props.persistBodySnippets() && s.failureReason() != null
                ? base + ": " + s.failureReason() : base;
        }
        var badAssertion = outcome.assertions().stream().filter(a -> !a.passed()).findFirst();
        if (badAssertion.isPresent()) {
            BrowserAssertionOutcome a = badAssertion.get();
            String base = a.type() + " on " + a.selectorDescription() + " expected " + a.expected();
            return props.persistBodySnippets() ? base + " got " + a.actual() : base;
        }
        return "browser scenario failed";
    }

    private BrowserRunMetadata partialMetadata(BrowserTestSnapshot spec) {
        int planned = spec.steps() == null ? 0 : spec.steps().size();
        return new BrowserRunMetadata(List.<BrowserStepOutcome>of(), List.<BrowserAssertionOutcome>of(),
            null, planned, 0, null, 0L, null, 0L);
    }

    private CaseExecutionResult result(CaseExecutionContext ctx, long start, CaseStatus status,
                                       String reason, BrowserRunMetadata meta) {
        return result(ctx, start, status, reason, meta, SideEffectClass.NONE_OBSERVED);
    }

    private CaseExecutionResult result(CaseExecutionContext ctx, long start, CaseStatus status,
                                       String reason, BrowserRunMetadata meta, SideEffectClass sideEffect) {
        var c = ctx.testCase();
        return new CaseExecutionResult(c.testCaseId(), c.name(), c.orderIndex(), status,
            Duration.ofNanos(System.nanoTime() - start), null, null, List.of(), reason, meta,
            sideEffect, ctx.attemptEpoch());
    }

    /** Resolves (and discards) each FILL {@code secretValue} to decide BLOCKED vs run. */
    private void preflightSecrets(List<BrowserStep> steps) {
        if (steps == null) {
            return;
        }
        for (BrowserStep s : steps) {
            if (s.action() == BrowserStep.Action.FILL && s.secretValue() != null) {
                secretResolver.resolve(s.secretValue().key());
            }
        }
    }

    private static boolean anyInteractiveStepAttempted(BrowserRunOutcome outcome) {
        return outcome.steps().stream().anyMatch(s ->
            (s.action() == BrowserStep.Action.CLICK
                || s.action() == BrowserStep.Action.SELECT
                || s.action() == BrowserStep.Action.PRESS_KEY)
            && !(s.status() == BrowserStepStatus.TIMEOUT
                && s.failureReason() != null && s.failureReason().contains("not executed")));
    }

    private static String pathStr(Path p) {
        return p == null ? null : p.toString();
    }
}
