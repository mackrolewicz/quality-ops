package com.qualityops.api.environment.application.port.out;

/**
 * ADR-008 §3 — outbound-port for a single health probe against an environment's
 * {@code base_url}. The SSRF guard runs in the application service <em>before</em>
 * this port is invoked; an adapter only performs the HTTP exchange.
 */
public interface EnvironmentHealthProbe {

    ProbeResult probe(String baseUrl);

    /**
     * @param reachable  whether a status line was received at all
     * @param httpStatus the response status code, or {@code null} when unreachable
     * @param latencyMs  wall-clock milliseconds spent on the exchange
     * @param error      short failure token (exception simple name), or {@code null} on success
     */
    record ProbeResult(boolean reachable, Integer httpStatus, Integer latencyMs, String error) {}
}
