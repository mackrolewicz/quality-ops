package com.qualityops.worker.execution.adapter.out.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.Locator;
import com.qualityops.events.BrowserStep;
import com.qualityops.worker.execution.application.port.out.PlaywrightBrowser;
import com.qualityops.worker.execution.application.port.out.SecretResolver;
import com.qualityops.worker.execution.domain.BrowserAssertionOutcome;
import com.qualityops.worker.execution.domain.BrowserRunCommand;
import com.qualityops.worker.execution.domain.BrowserRunOutcome;
import com.qualityops.worker.execution.domain.BrowserRunOutcome.Status;
import com.qualityops.worker.execution.domain.BrowserStepOutcome;
import com.qualityops.worker.execution.domain.BrowserStepStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The confined Playwright adapter: ALL Playwright / Browser / BrowserContext /
 *  Page / Locator access happens on the single {@code playwright-*} thread. */
@Component
class PlaywrightBrowserDriver implements PlaywrightBrowser {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserDriver.class);
    private static final Duration RECYCLE_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService playwrightExecutor;
    private final TargetValidator validator;
    private final Redactor redactor;
    private final SecretResolver secretResolver;
    private final SelectorMapper selectors = new SelectorMapper();
    private final BrowserAssertionEvaluator assertionEvaluator;
    private final Object lock = new Object();
    private Playwright playwright;   // guarded by lock, lazy
    private Browser browser;         // guarded by lock, lazy

    PlaywrightBrowserDriver(ExecutorService playwrightExecutor, TargetValidator validator, Redactor redactor,
                            SecretResolver secretResolver) {
        this.playwrightExecutor = playwrightExecutor;
        this.validator = validator;
        this.redactor = redactor;
        this.secretResolver = secretResolver;
        this.assertionEvaluator = new BrowserAssertionEvaluator(selectors, redactor);
    }

    @Override
    public BrowserRunOutcome run(BrowserRunCommand cmd) {
        if (Thread.currentThread().getName().startsWith("playwright-")) {
            return runOnThread(cmd);
        }
        try {
            return playwrightExecutor.submit(() -> runOnThread(cmd)).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("playwright driver fault", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("playwright driver interrupted", e);
        }
    }

    private BrowserRunOutcome runOnThread(BrowserRunCommand cmd) {
        Browser b = sharedBrowser(cmd.launchTimeoutMillis(), cmd.headless());
        BrowserContext context = b.newContext(new Browser.NewContextOptions());
        Page page = null;
        boolean tracing = false;
        List<BrowserStepOutcome> steps = new ArrayList<>();
        List<BrowserAssertionOutcome> asserts = new ArrayList<>();
        try {
            if (cmd.blockPrivateSubresources()) {
                installSubresourceGuard(context);
            }
            if (cmd.captureTrace()) {
                context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
                tracing = true;
            }
            page = context.newPage();
            page.setDefaultTimeout(cmd.stepTimeoutMillis());
            page.setDefaultNavigationTimeout(cmd.navigationTimeoutMillis());

            long deadline = System.nanoTime() + Duration.ofMillis(cmd.scenarioDeadlineMillis()).toNanos();
            page.navigate(cmd.startUrl());
            steps = runSteps(page, cmd.steps(), deadline);
            asserts = assertionEvaluator.evaluate(page, cmd.assertions(), cmd.persistTextSnippets());

            boolean failed = steps.stream().anyMatch(s -> s.status() != BrowserStepStatus.PASSED)
                || asserts.stream().anyMatch(a -> !a.passed());

            Written shot = maybeScreenshot(page, cmd, failed);
            Written trace = stopTrace(context, cmd, tracing, failed);
            tracing = false;

            return new BrowserRunOutcome(steps, asserts, redactor.url(safePageUrl(page)),
                shot.path(), shot.bytes(), trace.path(), trace.bytes(), Status.COMPLETED, null);
        } catch (TimeoutError e) {
            Written shot = maybeScreenshot(page, cmd, true);
            return new BrowserRunOutcome(steps, asserts, redactor.url(safePageUrl(page)),
                shot.path(), shot.bytes(), null, 0L, Status.TIMED_OUT, null);
        } catch (PlaywrightException e) {
            Written shot = maybeScreenshot(page, cmd, true);
            return new BrowserRunOutcome(steps, asserts, redactor.url(safePageUrl(page)),
                shot.path(), shot.bytes(), null, 0L, Status.FAULT, redactor.value(e.getMessage()));
        } finally {
            if (tracing) {
                stopTracingQuietly(context);
            }
            if (page != null) {
                closeQuietly("page", page::close);
            }
            closeQuietly("context", context::close);
        }
    }

    /** Aborts any page sub-resource request whose host resolves to a denylisted
     *  address (loopback / link-local / private / metadata), so an allowed page
     *  cannot pull {@code http://169.254.169.254/…}. Fails closed on an
     *  unresolvable host. */
    private void installSubresourceGuard(BrowserContext context) {
        var hostCache = new ConcurrentHashMap<String, Boolean>();
        context.route("**/*", route -> {
            String host = hostOf(route.request().url());
            boolean denied = host == null || hostCache.computeIfAbsent(host, this::isDeniedHost);
            if (denied) {
                route.abort();
            } else {
                route.resume();
            }
        });
    }

    private List<BrowserStepOutcome> runSteps(Page page, List<BrowserStep> plan, long deadline) {
        var out = new ArrayList<BrowserStepOutcome>(plan.size());
        boolean deadlineHit = false;
        for (int i = 0; i < plan.size(); i++) {
            BrowserStep step = plan.get(i);
            String desc = selectors.describe(step.target());
            if (deadlineHit || System.nanoTime() > deadline) {
                deadlineHit = true;
                out.add(new BrowserStepOutcome(i, step.action(), desc, BrowserStepStatus.TIMEOUT, 0L,
                    "not executed (scenario deadline reached)"));
                continue;
            }
            long started = System.nanoTime();
            BrowserStepStatus status;
            String reason = null;
            try {
                dispatch(page, step);
                status = BrowserStepStatus.PASSED;
            } catch (TimeoutError te) {
                status = BrowserStepStatus.TIMEOUT;
                reason = redactor.value(te.getMessage());
            } catch (PlaywrightException | IllegalArgumentException pe) {
                status = BrowserStepStatus.ERROR;
                reason = redactor.value(pe.getMessage());
            }
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            out.add(new BrowserStepOutcome(i, step.action(), desc, status, elapsedMs, reason));
        }
        return out;
    }

    private void dispatch(Page page, BrowserStep step) {
        switch (step.action()) {
            case NAVIGATE -> page.navigate(step.value());
            case CLICK -> selectors.toLocator(page, step.target()).click();
            case FILL -> {
                // Resolve a secretRef into a local ONLY here, at the point of use.
                String value = step.secretValue() != null
                    ? secretResolver.resolve(step.secretValue().key())
                    : step.value();
                selectors.toLocator(page, step.target()).fill(value);
            }
            case SELECT -> selectors.toLocator(page, step.target()).selectOption(step.value());
            case PRESS_KEY -> {
                if (step.target() != null) {
                    selectors.toLocator(page, step.target()).press(step.key());
                } else {
                    page.keyboard().press(step.key());
                }
            }
        }
    }

    private Browser sharedBrowser(long launchTimeoutMillis, boolean headless) {
        synchronized (lock) {
            if (playwright == null) {
                playwright = Playwright.create();
            }
            if (browser == null || !browser.isConnected()) {
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setTimeout(launchTimeoutMillis));
            }
            return browser;
        }
    }

    private Written maybeScreenshot(Page page, BrowserRunCommand cmd, boolean failed) {
        if (page == null || !failed || !cmd.screenshotOnFailure()) {
            return Written.NONE;
        }
        try {
            var opts = new Page.ScreenshotOptions().setFullPage(true);
            if (cmd.secretCase()) {
                opts.setMask(secretFillLocators(page, cmd));
            }
            byte[] png = page.screenshot(opts);
            return writeCapped(png, cmd, ".png");
        } catch (PlaywrightException e) {
            log.debug("screenshot failed", e);
            return Written.NONE;
        }
    }

    private Written stopTrace(BrowserContext context, BrowserRunCommand cmd, boolean tracing, boolean failed) {
        if (!tracing) {
            return Written.NONE;
        }
        if (!failed) {
            stopTracingQuietly(context);
            return Written.NONE;
        }
        try {
            Files.createDirectories(cmd.artifactDir());
            Path tracePath = cmd.artifactDir()
                .resolve(cmd.executionId() + "-" + cmd.caseId() + "-" + System.nanoTime() + "-trace.zip");
            context.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
            long size = Files.exists(tracePath) ? Files.size(tracePath) : 0L;
            if (size > cmd.artifactMaxBytes()) {
                Files.deleteIfExists(tracePath);
                return new Written(null, size);
            }
            return new Written(tracePath, size);
        } catch (IOException | PlaywrightException e) {
            log.debug("trace stop/write failed", e);
            stopTracingQuietly(context);
            return Written.NONE;
        }
    }

    private Written writeCapped(byte[] data, BrowserRunCommand cmd, String suffix) {
        try {
            Files.createDirectories(cmd.artifactDir());
            if (data.length > cmd.artifactMaxBytes()) {
                return new Written(null, data.length);
            }
            Path p = cmd.artifactDir()
                .resolve(cmd.executionId() + "-" + cmd.caseId() + "-" + System.nanoTime() + suffix);
            Files.write(p, data);
            return new Written(p, data.length);
        } catch (IOException e) {
            log.debug("artifact write failed", e);
            return new Written(null, data.length);
        }
    }

    /** Locators for every FILL target that carries a secretValue — painted over in
     *  a failure screenshot (best-effort input masking, ADR-005 §4.4). */
    private List<Locator> secretFillLocators(Page page, BrowserRunCommand cmd) {
        var masks = new ArrayList<Locator>();
        for (BrowserStep s : cmd.steps()) {
            if (s.action() == BrowserStep.Action.FILL && s.secretValue() != null && s.target() != null) {
                try {
                    masks.add(selectors.toLocator(page, s.target()));
                } catch (IllegalArgumentException ignored) {
                    // an unmappable selector simply is not masked
                }
            }
        }
        return masks;
    }

    private boolean isDeniedHost(String host) {
        try {
            var addrs = validator.resolve(host);
            return validator.validateResolved(host, addrs) instanceof TargetValidator.Blocked;
        } catch (UnknownHostException e) {
            return true;   // fail closed
        }
    }

    /** Hard kill of the shared browser after a scenario timeout or driver fault.
     *  {@code browser.close()} is run on the confined thread with a bounded wait: if
     *  that thread is wedged in a Playwright call the close cannot complete, so we
     *  abandon the wedged browser <em>and</em> its Playwright driver (a fresh
     *  {@link Playwright#create()} happens on the next run). The {@code browser} ref
     *  is cleared up front so the next {@link #sharedBrowser} relaunches regardless. */
    @Override
    public void forceRecycle() {
        final Browser doomed;
        synchronized (lock) {
            doomed = browser;
            browser = null;
        }
        if (doomed == null) {
            return;
        }
        Runnable closeTask = doomed::close;
        try {
            playwrightExecutor.submit(closeTask)
                .get(RECYCLE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("browser.close timed out after {} — abandoning the wedged browser and driver",
                RECYCLE_CLOSE_TIMEOUT);
            synchronized (lock) {
                playwright = null;
            }
        } catch (ExecutionException e) {
            log.warn("browser.close failed during recycle — abandoning the driver", e.getCause());
            synchronized (lock) {
                playwright = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void close() {
        synchronized (lock) {
            if (browser != null) {
                closeQuietly("browser", browser::close);
            }
            if (playwright != null) {
                closeQuietly("playwright", playwright::close);
            }
            browser = null;
            playwright = null;
        }
    }

    @Override
    public int openContextCount() {
        synchronized (lock) {
            return browser == null ? 0 : browser.contexts().size();
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String safePageUrl(Page page) {
        if (page == null) {
            return "about:blank";
        }
        try {
            return page.url();
        } catch (PlaywrightException e) {
            return "about:blank";
        }
    }

    /** Playwright's {@code close()} overrides declare no checked exception, so the
     *  callback is a plain {@link Runnable} and only the runtime
     *  {@link PlaywrightException} needs catching. */
    private void closeQuietly(String what, Runnable close) {
        try {
            close.run();
        } catch (PlaywrightException e) {
            log.debug("{} close failed", what, e);
        }
    }

    private void stopTracingQuietly(BrowserContext context) {
        try {
            context.tracing().stop();
        } catch (PlaywrightException e) {
            log.debug("tracing stop failed", e);
        }
    }

    private record Written(Path path, long bytes) {
        static final Written NONE = new Written(null, 0L);
    }
}
