package com.qualityops.api.scm.adapter.out.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.RepoExecApiProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** ADR-009 §4 — shared JDK {@link HttpClient} plumbing for the provider SCM
 *  adapters: {@code followRedirects(NEVER)}, connect + request timeout =
 *  {@code scm.http-timeout}, bounded response body read. */
abstract class AbstractHttpScmAdapter {

    /** Commit / repo JSON is small; anything larger is a misbehaving endpoint. */
    private static final int MAX_BODY_BYTES = 1_048_576;

    private final HttpClient http;
    private final Duration timeout;
    protected final ObjectMapper json;
    protected final RepoExecApiProperties props;

    protected AbstractHttpScmAdapter(RepoExecApiProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.timeout = props.scm() != null && props.scm().httpTimeout() != null
            ? props.scm().httpTimeout() : Duration.ofSeconds(10);
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(this.timeout)
            .build();
    }

    protected record HttpOutcome(int status, JsonNode body) {}

    protected HttpOutcome get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(builder::header);
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body() == null ? new byte[0] : response.body();
        if (raw.length > MAX_BODY_BYTES) {
            raw = java.util.Arrays.copyOf(raw, MAX_BODY_BYTES);
        }
        JsonNode node = raw.length == 0 ? json.nullNode()
            : json.readTree(new String(raw, StandardCharsets.UTF_8));
        return new HttpOutcome(response.statusCode(), node);
    }

    protected static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
