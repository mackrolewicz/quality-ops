package com.qualityops.worker.support;

import com.qualityops.worker.execution.application.port.out.SecretResolver;
import com.qualityops.worker.execution.exception.SecretNotFoundException;

import java.util.HashMap;
import java.util.Map;

/** Test double for {@link SecretResolver}: resolves from an in-memory map,
 *  throws {@link SecretNotFoundException} for anything else. Records every key
 *  it was asked to resolve. */
public final class StubSecretResolver implements SecretResolver {

    private final Map<String, String> values = new HashMap<>();
    private final java.util.List<String> requestedKeys = new java.util.ArrayList<>();

    public StubSecretResolver() {}

    public StubSecretResolver(Map<String, String> initial) {
        values.putAll(initial);
    }

    public StubSecretResolver with(String key, String value) {
        values.put(key, value);
        return this;
    }

    @Override
    public String resolve(String key) throws SecretNotFoundException {
        requestedKeys.add(key);
        var v = values.get(key);
        if (v == null) {
            throw new SecretNotFoundException(key);
        }
        return v;
    }

    public java.util.List<String> requestedKeys() {
        return java.util.List.copyOf(requestedKeys);
    }
}
