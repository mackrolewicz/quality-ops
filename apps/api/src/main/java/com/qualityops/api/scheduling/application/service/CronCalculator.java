package com.qualityops.api.scheduling.application.service;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** The only class that touches Spring's CronExpression. 6-field cron (seconds..
 *  day-of-week), IANA time zone, DST handled by java.time zone rules. */
@Component
public class CronCalculator {

    private static final Duration PREV_LOOKBACK = Duration.ofDays(400);
    private static final int MAX_PREV_ITERATIONS = 2_000_000;
    static final int MAX_PREVIEW = 50;

    /** Next occurrence strictly after {@code from}, in {@code zone}. */
    public Instant next(String cron, String zone, Instant from) {
        var z = ZoneId.of(zone);
        ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.ofInstant(from, z));
        if (next == null) {
            throw new IllegalArgumentException("cron '" + cron + "' has no future occurrence");
        }
        return next.toInstant();
    }

    /** The next {@code count} occurrences after {@code from}; count clamped 1..50. */
    public List<Instant> nextN(String cron, String zone, Instant from, int count) {
        int n = Math.max(1, Math.min(count, MAX_PREVIEW));
        var z = ZoneId.of(zone);
        var expr = CronExpression.parse(cron);
        var out = new ArrayList<Instant>(n);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(from, z);
        for (int i = 0; i < n; i++) {
            cursor = expr.next(cursor);
            if (cursor == null) {
                break;
            }
            out.add(cursor.toInstant());
        }
        return List.copyOf(out);
    }

    /** The most recent occurrence at or before {@code at} — the catch-up fire slot.
     *  CronExpression only iterates forward, so walk forward from a lookback window. */
    public Instant previousOccurrence(String cron, String zone, Instant at) {
        var z = ZoneId.of(zone);
        var expr = CronExpression.parse(cron);
        var ceiling = ZonedDateTime.ofInstant(at, z);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(at.minus(PREV_LOOKBACK), z);
        ZonedDateTime last = null;
        for (int i = 0; i < MAX_PREV_ITERATIONS; i++) {
            ZonedDateTime candidate = expr.next(cursor);
            if (candidate == null || candidate.isAfter(ceiling)) {
                break;
            }
            last = candidate;
            cursor = candidate;
        }
        if (last == null) {
            throw new IllegalArgumentException(
                "cron '" + cron + "' has no occurrence within the lookback window before " + at);
        }
        return last.toInstant();
    }
}
