package com.qualityops.events;

/** One imperative step in a declarative browser scenario.
 *  NAVIGATE: value = absolute URL, target = null.
 *  CLICK: target. FILL: target + (value XOR secretValue). SELECT: target + value.
 *  PRESS_KEY: key (target optional — focused element when null).
 *  For FILL, {@code secretValue} is the opaque alternative to {@code value};
 *  the Worker resolves it immediately before {@code fill()} and never records
 *  the plaintext. */
public record BrowserStep(Action action, Selector target, String value, String key, SecretRef secretValue) {
    public enum Action { NAVIGATE, CLICK, FILL, SELECT, PRESS_KEY }

    /** Convenience — no secret. Keeps every 4-arg call site compiling. */
    public BrowserStep(Action action, Selector target, String value, String key) {
        this(action, target, value, key, null);
    }
}
