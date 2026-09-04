package com.qualityops.worker.execution.domain;

import java.util.Map;

public record RequestMetadata(String method, String sanitisedUrl,
                              Map<String, String> redactedHeaders, long requestBodyBytes) {}
