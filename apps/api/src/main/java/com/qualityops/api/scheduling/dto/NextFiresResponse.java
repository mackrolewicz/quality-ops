package com.qualityops.api.scheduling.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NextFiresResponse(UUID scheduleId, List<Instant> fireTimes) {}
