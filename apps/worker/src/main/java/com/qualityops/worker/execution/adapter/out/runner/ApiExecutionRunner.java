package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.ApiAssertion;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.HttpHeader;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.AssertionOutcome;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.adapter.out.runner.BoundedBodySubscriber.BoundedBody;
import com.qualityops.worker.execution.application.port.out.SecretResolver;
import com.qualityops.worker.execution.domain.RequestMetadata;
import com.qualityops.worker.execution.domain.ResponseMetadata;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;
import com.qualityops.worker.execution.exception.SecretNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes one API-test case over real HTTP via the JDK {@link HttpClient},
 *  fronted by SSRF validation and redaction. Never throws for a test failure,
 *  timeout, blocked target or connection error — those are encoded in the
 *  returned {@link CaseExecutionResult}. Throws {@link ExecutionHarnessException}
 *  only for a genuine worker fault (the orchestrator maps that to runs.failed). */
@Component
public class ApiExecutionRunner implements ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiExecutionRunner.class);

    /** Header names the JDK HttpClient refuses to let callers set. */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
        "connection", "content-length", "expect", "host", "upgrade");
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS", "DELETE");

    /** Idempotent by HTTP semantics ⇒ a transport-level failure is retry-safe (ADR-005 §3.3). */
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");

    /** How long a single {@code future.get} waits before the loop re-checks the
     *  cancellation flag and the absolute deadline. Bounds cancellation latency
     *  without busy-spinning (each wait parks the thread). */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** Grace added to the per-request timeout for the loop's own monotonic
     *  backstop, so the JDK client's {@code HttpRequest.timeout()} normally fires
     *  first (cleaner {@link HttpTimeoutException}); the backstop only guarantees
     *  termination if the client's timeout does not fire. */
    private static final Duration DEADLINE_GRACE = Duration.ofSeconds(1);

    private final HttpClient httpClient;
    private final TargetValidator validator;
    private final Redactor redactor;
    private final AssertionEvaluator assertions;
    private final WorkerExecutionProperties props;
    private final SecretResolver secretResolver;

    public ApiExecutionRunner(HttpClient executionHttpClient, TargetValidator validator,
                              Redactor redactor, AssertionEvaluator assertions,
                              WorkerExecutionProperties props, SecretResolver secretResolver) {
        this.httpClient = executionHttpClient;
        this.validator = validator;
        this.redactor = redactor;
        this.assertions = assertions;
        this.props = props;
        this.secretResolver = secretResolver;
    }

    @Override
    public RunnerKind kind() {
        return RunnerKind.API;
    }

    @Override
    public CaseExecutionResult execute(CaseExecutionContext ctx) throws ExecutionHarnessException {
        long start = System.nanoTime();
        var spec = ctx.testCase().apiRequest();
        if (spec == null) {
            return result(ctx, start, CaseStatus.BLOCKED, null, null, List.of(),
                "no API request on this case", SideEffectClass.NONE_OBSERVED);
        }

        String method = spec.method() == null ? "GET" : spec.method().toUpperCase(Locale.ROOT);
        SideEffectClass transportSideEffect = IDEMPOTENT_METHODS.contains(method)
            ? SideEffectClass.NONE_OBSERVED : SideEffectClass.POSSIBLE;

        var stage1 = validator.validateUrl(spec.url());
        if (stage1 instanceof TargetValidator.Blocked b) {
            return result(ctx, start, CaseStatus.BLOCKED, null, null, List.of(), b.safeReason(),
                SideEffectClass.NONE_OBSERVED);
        }
        URI uri = ((TargetValidator.Allowed) stage1).uri();
        String host = uri.getHost();

        final List<java.net.InetAddress> resolved;
        try {
            resolved = validator.resolve(host);
        } catch (UnknownHostException e) {
            return result(ctx, start, CaseStatus.ERROR, null, null, List.of(),
                "could not resolve " + host, SideEffectClass.NONE_OBSERVED);
        }
        if (validator.validateResolved(host, resolved) instanceof TargetValidator.Blocked b) {
            return result(ctx, start, CaseStatus.BLOCKED, null, null, List.of(), b.safeReason(),
                SideEffectClass.NONE_OBSERVED);
        }

        if (ctx.cancellation().isCancelled()) {
            return result(ctx, start, CaseStatus.ERROR, null, null, List.of(), "run cancelled",
                SideEffectClass.NONE_OBSERVED);
        }

        // Resolve secretRef headers just-in-time. Unresolvable ⇒ BLOCKED (deterministic
        // config problem — never retried); the request is not sent.
        final Map<String, String> secretHeaders;
        try {
            secretHeaders = resolveSecretHeaders(spec);
        } catch (SecretNotFoundException e) {
            return result(ctx, start, CaseStatus.BLOCKED, null, null, List.of(), e.getMessage(),
                SideEffectClass.NONE_OBSERVED);
        }
        Set<String> secretHeaderNames = lowerCaseKeys(secretHeaders.keySet());

        // Everything past the cheap SSRF/cancel guards is wrapped: a malformed
        // case (bad assertion/header/URL shape) must never poison the run — it
        // becomes a single ERROR case, redaction-safe, and the run still completes.
        try {
            HttpRequest request = buildRequest(ctx, spec, secretHeaders);
            var reqMeta = requestMetadata(spec, secretHeaderNames);
            HttpResponse<BoundedBody> response;
            try {
                response = send(ctx, request);
            } catch (HttpTimeoutException e) {
                return result(ctx, start, CaseStatus.TIMEOUT, reqMeta, null, List.of(),
                    "request exceeded " + ctx.effectiveTimeout().toMillis() + " ms", transportSideEffect);
            } catch (CancelledException e) {
                return result(ctx, start, CaseStatus.ERROR, reqMeta, null, List.of(), "run cancelled",
                    SideEffectClass.NONE_OBSERVED);
            } catch (ConnectException | SSLException e) {
                return result(ctx, start, CaseStatus.ERROR, reqMeta, null, List.of(), "connection error",
                    SideEffectClass.NONE_OBSERVED);
            } catch (IOException e) {
                return result(ctx, start, CaseStatus.ERROR, reqMeta, null, List.of(), "connection error",
                    transportSideEffect);
            }
            return evaluate(ctx, start, spec, reqMeta, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionHarnessException("api execution interrupted", e);
        } catch (RuntimeException e) {
            log.warn("Case {} raised an unexpected {} — recording ERROR",
                ctx.testCase().testCaseId(), e.getClass().getSimpleName());
            return result(ctx, start, CaseStatus.ERROR, null, null, List.of(), "case evaluation error",
                SideEffectClass.NONE_OBSERVED);
        }
    }

    /** key = header name, value = resolved plaintext. Lives only for this invocation. */
    private Map<String, String> resolveSecretHeaders(ApiRequestSnapshot spec) {
        if (spec.headers() == null) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, String>();
        for (HttpHeader h : spec.headers()) {
            if (h.secretRef() != null && h.name() != null) {
                out.put(h.name(), secretResolver.resolve(h.secretRef().key()));
            }
        }
        return out;
    }

    private static Set<String> lowerCaseKeys(Set<String> names) {
        var out = new java.util.HashSet<String>();
        names.forEach(n -> out.add(n.toLowerCase(Locale.ROOT)));
        return out;
    }

    // ---- request construction ----

    private HttpRequest buildRequest(CaseExecutionContext ctx, ApiRequestSnapshot spec,
                                     Map<String, String> secretHeaders) {
        String method = spec.method().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher bodyPublisher = spec.body() == null || BODYLESS_METHODS.contains(method)
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8);

        var builder = HttpRequest.newBuilder(URI.create(spec.url()))
            .method(method, bodyPublisher)
            .timeout(ctx.effectiveTimeout());

        if (spec.headers() != null) {
            for (HttpHeader h : spec.headers()) {
                if (h.name() == null || RESTRICTED_HEADERS.contains(h.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String value = secretHeaders.containsKey(h.name())
                    ? secretHeaders.get(h.name())
                    : (h.value() == null ? "" : h.value());
                try {
                    builder.header(h.name(), value);
                } catch (IllegalArgumentException dropped) {
                    log.debug("Dropped disallowed request header {}", redactor.isSensitiveHeader(h.name())
                        ? "***" : h.name());
                }
            }
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            builder.header("Idempotency-Key", ctx.executionId().toString());
        }
        return builder.build();
    }

    private RequestMetadata requestMetadata(ApiRequestSnapshot spec, Set<String> secretHeaderNames) {
        var raw = new LinkedHashMap<String, String>();
        if (spec.headers() != null) {
            spec.headers().forEach(h -> raw.put(h.name(), h.value() == null ? "" : h.value()));
        }
        var redacted = new LinkedHashMap<>(redactor.headers(raw));
        // Hard rule (ADR-005 §4.4): a header sourced from a secretRef is ALWAYS masked,
        // independent of the redaction denylist.
        redacted.replaceAll((k, v) -> secretHeaderNames.contains(k.toLowerCase(Locale.ROOT)) ? "***" : v);
        long bodyBytes = spec.body() == null ? 0L : spec.body().getBytes(StandardCharsets.UTF_8).length;
        return new RequestMetadata(spec.method(), redactor.url(spec.url()), redacted, bodyBytes);
    }

    // ---- send with cooperative cancellation ----

    private HttpResponse<BoundedBody> send(CaseExecutionContext ctx, HttpRequest request)
            throws IOException, InterruptedException {
        // Bounded, streaming body handler: retains at most maxResponseBytes and
        // then cancels the transfer — the full response is never buffered.
        CompletableFuture<HttpResponse<BoundedBody>> future = httpClient.sendAsync(
            request, responseInfo -> new BoundedBodySubscriber(ctx.maxResponseBytes()));

        // Absolute monotonic backstop: guarantees the exchange terminates even if
        // the JDK client's own HttpRequest.timeout() never fires. Not a busy
        // spin — each iteration parks in future.get for up to POLL_INTERVAL.
        long deadlineNanos = System.nanoTime()
            + ctx.effectiveTimeout().plus(DEADLINE_GRACE).toNanos();
        try {
            while (true) {
                if (ctx.cancellation().isCancelled()) {
                    future.cancel(true);
                    throw new CancelledException();
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    future.cancel(true);
                    throw new HttpTimeoutException(
                        "request exceeded " + ctx.effectiveTimeout().toMillis() + " ms");
                }
                long waitNanos = Math.min(remainingNanos, POLL_INTERVAL.toNanos());
                try {
                    return future.get(waitNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException poll) {
                    // still in flight — loop re-checks cancellation and the deadline
                } catch (ExecutionException e) {
                    throw unwrap(e);
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    private static RuntimeException asRuntime(Throwable t) {
        return t instanceof RuntimeException re ? re : new ExecutionHarnessException("api runner fault", t);
    }

    private IOException unwrap(ExecutionException e) throws IOException, InterruptedException {
        Throwable cause = e.getCause();
        if (cause instanceof HttpTimeoutException hte) {
            throw hte;
        }
        if (cause instanceof IOException ioe) {
            throw ioe;
        }
        if (cause instanceof InterruptedException ie) {
            throw ie;
        }
        throw asRuntime(cause == null ? e : cause);
    }

    // ---- response evaluation ----

    private CaseExecutionResult evaluate(CaseExecutionContext ctx, long start, ApiRequestSnapshot spec,
                                         RequestMetadata reqMeta, HttpResponse<BoundedBody> response) {
        BoundedBody bounded = response.body();
        // retained is already bounded to maxResponseBytes; assertions and the
        // sample therefore see at most the first maxResponseBytes of the body.
        byte[] body = bounded == null ? new byte[0] : bounded.retained();
        long fullBytes = bounded == null ? 0L : bounded.totalBytes();
        boolean truncated = bounded != null && bounded.truncated();
        int sampleLen = (int) Math.min(body.length, props.responseBodySampleBytes());
        String rawSample = new String(body, 0, sampleLen, StandardCharsets.UTF_8);
        String redactedSample = redactor.body(rawSample);

        var rawHeaders = new LinkedHashMap<String, String>();
        response.headers().map().forEach((k, v) -> rawHeaders.put(k, String.join(",", v)));
        var lookupHeaders = new LinkedHashMap<String, String>();
        rawHeaders.forEach((k, v) -> lookupHeaders.put(k.toLowerCase(Locale.ROOT), v));

        var respMeta = new ResponseMetadata(response.statusCode(), redactor.headers(rawHeaders),
            fullBytes, redactedSample, truncated);

        String assertionBody = new String(body, StandardCharsets.UTF_8);
        List<ApiAssertion> declared = declaredAssertions(spec);
        List<AssertionOutcome> outcomes =
            assertions.evaluateAll(declared, response.statusCode(), assertionBody, lookupHeaders);

        boolean statusOk = statusMatches(spec, response.statusCode());
        boolean allAssertionsPassed = outcomes.stream().allMatch(AssertionOutcome::passed);

        // A response status line was seen ⇒ a retry could double-charge a side effect.
        if (statusOk && allAssertionsPassed) {
            return result(ctx, start, CaseStatus.PASSED, reqMeta, respMeta, outcomes, null,
                SideEffectClass.POSSIBLE);
        }
        String reason = firstFailureReason(spec, response.statusCode(), statusOk, outcomes);
        return result(ctx, start, CaseStatus.FAILED, reqMeta, respMeta, outcomes, reason,
            SideEffectClass.POSSIBLE);
    }

    private static List<ApiAssertion> declaredAssertions(ApiRequestSnapshot spec) {
        if (spec.assertions() != null && !spec.assertions().isEmpty()) {
            return spec.assertions();
        }
        if (spec.expectedStatus() != null) {
            return List.of(new ApiAssertion(ApiAssertion.Type.STATUS_EQUALS, "",
                String.valueOf(spec.expectedStatus())));
        }
        return List.of();
    }

    private static boolean statusMatches(ApiRequestSnapshot spec, int statusCode) {
        if (spec.expectedStatus() != null) {
            return spec.expectedStatus() == statusCode;
        }
        return statusCode >= 200 && statusCode < 300;
    }

    private String firstFailureReason(ApiRequestSnapshot spec, int statusCode, boolean statusOk,
                                      List<AssertionOutcome> outcomes) {
        var failed = outcomes.stream().filter(o -> !o.passed()).findFirst();
        if (failed.isPresent()) {
            var o = failed.get();
            if (o.type() == ApiAssertion.Type.BODY_CONTAINS && !props.persistBodySnippets()) {
                return o.type() + " expected " + redactor.value(o.expected());
            }
            return o.type() + " expected " + o.expected() + " got " + o.actual();
        }
        if (!statusOk) {
            String expected = spec.expectedStatus() != null ? String.valueOf(spec.expectedStatus()) : "2xx";
            return "STATUS_EQUALS expected " + expected + " got " + statusCode;
        }
        return "assertion failed";
    }

    private CaseExecutionResult result(CaseExecutionContext ctx, long start, CaseStatus status,
                                       RequestMetadata req, ResponseMetadata resp,
                                       List<AssertionOutcome> outcomes, String reason,
                                       SideEffectClass sideEffect) {
        var c = ctx.testCase();
        return new CaseExecutionResult(c.testCaseId(), c.name(), c.orderIndex(), status,
            Duration.ofNanos(System.nanoTime() - start), req, resp,
            outcomes == null ? List.of() : outcomes, reason, null, sideEffect, ctx.attemptEpoch());
    }

    /** Internal signal that a poll loop observed cancellation. */
    private static final class CancelledException extends RuntimeException {
    }
}
