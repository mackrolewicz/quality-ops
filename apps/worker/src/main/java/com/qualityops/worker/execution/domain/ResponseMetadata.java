package com.qualityops.worker.execution.domain;

import java.util.Map;

public record ResponseMetadata(int statusCode, Map<String, String> redactedHeaders,
                               long responseBodyBytes, String bodySample, boolean bodyTruncated) {}
