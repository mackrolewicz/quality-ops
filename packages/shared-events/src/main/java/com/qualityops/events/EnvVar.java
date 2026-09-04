package com.qualityops.events;

/** A non-secret environment variable injected into the framework container.
 *  Secret-backed variables use {@link SecretEnvVar} instead. */
public record EnvVar(String name, String value) {}
