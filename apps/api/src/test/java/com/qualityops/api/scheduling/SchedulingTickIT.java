package com.qualityops.api.scheduling;

import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.application.service.ScheduleFireService;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the schedule fire path (ADR-006 §1.4): a due RECURRING schedule produces
 * exactly one {@code run_queue} row, running the tick again fires nothing more
 * for the same logical occurrence (unique {@code schedule_fire (schedule_id,
 * fire_slot)}), and {@code next_fire_at} is advanced. Jobs are off their timers
 * ({@code jobs-enabled=false}); the test drives {@link ScheduleFireService}
 * exactly as {@code ScheduleTickJob.tick()} would.
 */
class SchedulingTickIT extends AbstractPostgresIT {

    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleFireService scheduleFireService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID projectId;
    private UUID suiteId;
    private UUID environmentId;
    private UUID createdBy;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        createdBy = ItFixtures.insertUser(jdbc, orgId);
        ItFixtures.insertCases(jdbc, orgId, suiteId, 2);
    }

    private UUID insertDueRecurringSchedule() {
        var nextFireAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        return jdbc.queryForObject("""
            INSERT INTO schedule (org_id, project_id, suite_id, environment_id, name, kind,
                                  cron_expression, time_zone, priority, catch_up_policy, enabled,
                                  next_fire_at, created_by)
            VALUES (?, ?, ?, ?, 'nightly', 'RECURRING', '0 * * * * *', 'UTC', 'NORMAL',
                    'SKIP_MISSED', TRUE, ?, ?)
            RETURNING id
            """, UUID.class, orgId, projectId, suiteId, environmentId,
            Timestamp.from(nextFireAt), createdBy);
    }

    private void runTick() {
        scheduleRepository.findDue(200).forEach(scheduleFireService::fire);
    }

    /**
     * A single synchronous tick can miss a just-committed row on a loaded
     * Testcontainers Postgres (the SELECT's snapshot races the autocommit INSERT).
     * The real {@code ScheduleTickJob} runs every 15s so a one-tick miss is
     * invisible in production; retry a few idempotent ticks here —
     * {@code schedule_fire}'s unique guard makes re-runs safe.
     */
    private void tickUntilFired(UUID scheduleId) {
        for (int i = 0; i < 50 && fireLedgerRows(scheduleId) == 0; i++) {
            runTick();
            if (fireLedgerRows(scheduleId) == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private int queueRows(UUID scheduleId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM run_queue WHERE schedule_id = ?", Integer.class, scheduleId);
    }

    private int fireLedgerRows(UUID scheduleId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM schedule_fire WHERE schedule_id = ?", Integer.class, scheduleId);
    }

    @Test
    void tick_dueRecurringSchedule_enqueuesExactlyOneRunAndAdvancesNextFireAt() {
        var scheduleId = insertDueRecurringSchedule();
        var beforeNextFire = jdbc.queryForObject(
            "SELECT next_fire_at FROM schedule WHERE id = ?", Instant.class, scheduleId);

        tickUntilFired(scheduleId);

        assertThat(queueRows(scheduleId)).isEqualTo(1);
        assertThat(fireLedgerRows(scheduleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE schedule_id = ?",
            String.class, scheduleId)).isEqualTo("QUEUED");
        var afterNextFire = jdbc.queryForObject(
            "SELECT next_fire_at FROM schedule WHERE id = ?", Instant.class, scheduleId);
        assertThat(afterNextFire).isAfter(beforeNextFire);
    }

    @Test
    void tick_retriedForTheSameOccurrence_firesOnlyOnce() {
        var scheduleId = insertDueRecurringSchedule();
        var originalSlot = jdbc.queryForObject(
            "SELECT next_fire_at FROM schedule WHERE id = ?", Instant.class, scheduleId);

        tickUntilFired(scheduleId);
        // Simulate a retried tick racing the SAME logical occurrence: reset
        // next_fire_at to the exact instant that first made the row due.
        jdbc.update("UPDATE schedule SET next_fire_at = ? WHERE id = ?",
            Timestamp.from(originalSlot), scheduleId);
        runTick();

        assertThat(fireLedgerRows(scheduleId)).isEqualTo(1);
        assertThat(queueRows(scheduleId)).isEqualTo(1);
    }
}
