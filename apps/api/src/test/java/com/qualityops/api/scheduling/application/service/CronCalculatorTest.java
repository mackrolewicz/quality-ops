package com.qualityops.api.scheduling.application.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronCalculatorTest {

    private final CronCalculator calc = new CronCalculator();

    @Test
    void next_dailyCronInUtc_returnsNextMidnight() {
        var from = Instant.parse("2026-03-10T08:00:00Z");

        var next = calc.next("0 0 0 * * *", "UTC", from);

        assertThat(next).isEqualTo(Instant.parse("2026-03-11T00:00:00Z"));
    }

    @Test
    void next_everyMinute_inWarsaw_advancesOneMinute() {
        var from = Instant.parse("2026-06-01T10:00:30Z");

        var next = calc.next("0 * * * * *", "Europe/Warsaw", from);

        assertThat(next).isEqualTo(Instant.parse("2026-06-01T10:01:00Z"));
    }

    @Test
    void next_springForwardGap_skipsTheDayWithNoValidLocalTime() {
        // Europe/Warsaw springs forward on 2026-03-29 (02:00 -> 03:00). A 02:30
        // daily cron has NO valid 2026-03-29 local time, so Spring's
        // CronExpression skips that day entirely; the next fire is 2026-03-30.
        var warsaw = ZoneId.of("Europe/Warsaw");
        var beforeTransition = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, warsaw).toInstant();

        var next = calc.next("0 30 2 * * *", "Europe/Warsaw", beforeTransition);

        assertThat(next).isAfter(beforeTransition);
        assertThat(next.atZone(warsaw).toLocalDate())
            .isEqualTo(java.time.LocalDate.of(2026, 3, 30));
    }

    @Test
    void next_fallBackOverlap_producesTwoDistinctInstantsOnTheTransitionDay() {
        // Europe/Warsaw falls back on 2026-10-25 (03:00 -> 02:00): the 02:30 local
        // time occurs twice (CEST then CET). CronExpression yields both as
        // distinct instants an hour apart on the same calendar day. The
        // schedule_fire (schedule_id, fire_slot) ledger keys on the instant, so
        // each is a distinct logical occurrence.
        var warsaw = ZoneId.of("Europe/Warsaw");
        var before = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, warsaw).toInstant();

        var first = calc.next("0 30 2 * * *", "Europe/Warsaw", before);
        var second = calc.next("0 30 2 * * *", "Europe/Warsaw", first);

        assertThat(first.atZone(warsaw).toLocalDate())
            .isEqualTo(java.time.LocalDate.of(2026, 10, 25));
        assertThat(second.atZone(warsaw).toLocalDate())
            .isEqualTo(java.time.LocalDate.of(2026, 10, 25));
        assertThat(java.time.Duration.between(first, second)).isEqualTo(java.time.Duration.ofHours(1));
    }

    @Test
    void nextN_countClampedToFifty() {
        var fires = calc.nextN("0 * * * * *", "UTC", Instant.parse("2026-01-01T00:00:00Z"), 999);

        assertThat(fires).hasSize(50);
        assertThat(fires.get(0)).isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));
    }

    @Test
    void nextN_countBelowOne_clampsToOne() {
        var fires = calc.nextN("0 0 0 * * *", "UTC", Instant.parse("2026-01-01T12:00:00Z"), 0);

        assertThat(fires).hasSize(1);
    }

    @Test
    void previousOccurrence_dailyCron_returnsMostRecentPastMidnight() {
        var at = Instant.parse("2026-05-20T15:30:00Z");

        var prev = calc.previousOccurrence("0 0 0 * * *", "UTC", at);

        assertThat(prev).isEqualTo(Instant.parse("2026-05-20T00:00:00Z"));
    }

    @Test
    void next_invalidCron_throwsIllegalArgument() {
        assertThatThrownBy(() -> calc.next("not a cron", "UTC", Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
