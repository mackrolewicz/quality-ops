package com.qualityops.events;

/** One HTTP header on a frozen API-request snapshot. Ordered list on the wire;
 *  repeated header names are legal. Exactly one of {@code value} /
 *  {@code secretRef} is set — when {@code secretRef} is non-null the Worker
 *  resolves the plaintext at execution time and the header is always masked in
 *  request metadata. */
public record HttpHeader(String name, String value, SecretRef secretRef) {

    /** Convenience — plaintext header. Keeps every 2-arg call site compiling. */
    public HttpHeader(String name, String value) {
        this(name, value, null);
    }
}
