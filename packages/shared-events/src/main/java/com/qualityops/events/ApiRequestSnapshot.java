package com.qualityops.events;

import java.util.List;

/** Frozen API-request spec for one snapshot case. Nullable on
 *  {@link TestCaseSnapshotItem} — absent ⇒ the case runs in the simulator. */
public record ApiRequestSnapshot(
        String method,                 // GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS
        String url,
        List<HttpHeader> headers,      // ordered; never null on a populated snapshot
        String body,                   // nullable
        Integer expectedStatus,        // nullable
        Integer timeoutMillis,         // nullable
        Long maxResponseBytes,         // nullable
        List<ApiAssertion> assertions  // nullable/empty
) {}
