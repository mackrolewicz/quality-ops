package com.qualityops.api.environment.adapter.out.probe;

import com.qualityops.api.config.EnvironmentHealthProperties;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ADR-008 §3 — JDK {@link HttpClient} probe. {@code followRedirects(NEVER)},
 * connect + per-request timeout = {@code probe-timeout}, {@code GET} with a
 * {@code HEAD} fallback on 405/501, response body discarded (no bytes retained).
 * The SSRF guard runs in {@code EnvironmentHealthService} before this is called.
 */
@Component
public class HttpEnvironmentHealthProbe implements EnvironmentHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpEnvironmentHealthProbe.class);

    private final HttpClient client;
    private final Duration timeout;

    public HttpEnvironmentHealthProbe(EnvironmentHealthProperties props) {
        this.timeout = props.probeTimeout();
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(props.probeTimeout())
            .build();
    }

    @Override
    public ProbeResult probe(String baseUrl) {
        long startNanos = System.nanoTime();
        try {
            HttpResponse<Void> response = send(baseUrl, "GET");
            if (response.statusCode() == 405 || response.statusCode() == 501) {
                response = send(baseUrl, "HEAD");
            }
            return new ProbeResult(true, response.statusCode(), elapsedMs(startNanos), null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("environment health probe to {} failed: {}", baseUrl, e.toString());
            return new ProbeResult(false, null, elapsedMs(startNanos), e.getClass().getSimpleName());
        }
    }

    private HttpResponse<Void> send(String baseUrl, String method) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(timeout)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("User-Agent", "QualityOps-HealthProbe")
            .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }
}
