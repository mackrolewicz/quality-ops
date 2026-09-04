package com.qualityops.events;

/** A secret-backed environment variable. {@code name} is author-chosen (not
 *  sensitive); {@code ref} is the opaque key the Worker resolves to a plaintext
 *  at execution time. The plaintext never enters an event, a config snapshot, a
 *  log line, or an artifact. */
public record SecretEnvVar(String name, SecretRef ref) {}
