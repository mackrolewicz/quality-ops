package com.qualityops.events;

/** Opaque reference to a credential resolved by the Worker at execution time.
 *  Carried on the wire in place of a plaintext {@code value}; the plaintext
 *  never enters an event, a config snapshot, a log line, or an artifact.
 *  {@code key} matches {@code [A-Z0-9_]{1,64}}. */
public record SecretRef(String key) {}
