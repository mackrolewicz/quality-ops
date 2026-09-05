package com.qualityops.worker.execution.application.port.out;

import com.qualityops.worker.execution.exception.SecretNotFoundException;

/** Resolves an opaque {@code secretRef} key to its plaintext at execution time.
 *  The plaintext must live only in a local variable at the point of use and
 *  never reach an event, a snapshot, a log line, request metadata, an outcome,
 *  or an artifact. */
public interface SecretResolver {

    /** @throws SecretNotFoundException if the key is unknown to every source. */
    String resolve(String key) throws SecretNotFoundException;
}
