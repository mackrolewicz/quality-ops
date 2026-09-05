package com.qualityops.api.webhook.application.service;

import com.qualityops.api.config.WebhookProperties;
import com.qualityops.api.webhook.domain.WebhookEventType;
import com.qualityops.api.webhook.domain.WebhookSignature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** ADR-007 §6.3 — JDK {@code HttpClient} POST with the four signature headers,
 *  bounded timeouts, redirects disabled. No new dependency. */
@Component
public class WebhookSender {

    private final WebhookProperties props;
    private final HttpClient http;

    public WebhookSender(WebhookProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public SendOutcome send(String url, String secret, WebhookEventType type, UUID deliveryId,
                            long ts, String body) {
        var req = HttpRequest.newBuilder(URI.create(url))
            .timeout(props.requestTimeout())
            .header("Content-Type", "application/json")
            .header("X-QualityOps-Event", type.wireName())
            .header("X-QualityOps-Delivery", deliveryId.toString())
            .header("X-QualityOps-Timestamp", Long.toString(ts))
            .header("X-QualityOps-Signature", WebhookSignature.sign(secret, ts, body))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            int s = resp.statusCode();
            return (s >= 200 && s < 300)
                ? new SendOutcome(true, null)
                : new SendOutcome(false, "HTTP " + s);
        } catch (IOException e) {
            return new SendOutcome(false, truncate(e.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendOutcome(false, truncate(e.toString()));
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    public record SendOutcome(boolean delivered, String error) {}
}
