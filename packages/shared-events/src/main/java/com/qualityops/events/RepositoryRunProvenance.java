package com.qualityops.events;

import java.time.Instant;

/** Run-level telemetry for one repository execution attempt. Drives the
 *  {@code repository_run} telemetry columns; carried on {@link ResultChunkEvent}
 *  and re-carried on {@link RunCompletedEvent} so the terminal alone can
 *  reconstruct it if every chunk is lost. */
public record RepositoryRunProvenance(
        String imageDigest,        // nullable until the image is resolved
        Integer exitCode,          // nullable ⇒ the framework container never ran
        int itemsTotal,
        int itemsPassed,
        int itemsFailed,
        int itemsSkipped,
        Instant checkoutAt,        // nullable
        Instant startedAt,         // nullable
        Instant finishedAt         // nullable
) {}
