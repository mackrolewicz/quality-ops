package com.qualityops.api.scheduling.application.service;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.exception.ProjectNotFoundException;
import com.qualityops.api.scheduling.application.port.out.ScheduleFireLedger;
import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.domain.CatchUpPolicy;
import com.qualityops.api.scheduling.domain.Schedule;
import com.qualityops.api.scheduling.domain.ScheduleKind;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** ADR-006 §1.4–§1.5. One logical occurrence per call. The guard + ledger insert
 *  + enqueue + next_fire_at advance are ONE transaction; an enqueue failure rolls
 *  the schedule_fire row back and the slot re-fires next tick (bounded — an
 *  invalid target is caught by the abandon check, not by infinite retry). */
@Service
public class ScheduleFireService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFireService.class);

    private final ScheduleRepository scheduleRepository;
    private final ScheduleFireLedger fireLedger;
    private final EnqueueRunUseCase enqueueRunUseCase;
    private final CronCalculator cron;
    private final GetProjectUseCase getProjectUseCase;
    private final GetTestSuiteUseCase getTestSuiteUseCase;
    private final GetEnvironmentUseCase getEnvironmentUseCase;
    private final QueueMetrics metrics;

    public ScheduleFireService(ScheduleRepository scheduleRepository,
                               ScheduleFireLedger fireLedger,
                               EnqueueRunUseCase enqueueRunUseCase,
                               CronCalculator cron,
                               GetProjectUseCase getProjectUseCase,
                               GetTestSuiteUseCase getTestSuiteUseCase,
                               GetEnvironmentUseCase getEnvironmentUseCase,
                               QueueMetrics metrics) {
        this.scheduleRepository = scheduleRepository;
        this.fireLedger = fireLedger;
        this.enqueueRunUseCase = enqueueRunUseCase;
        this.cron = cron;
        this.getProjectUseCase = getProjectUseCase;
        this.getTestSuiteUseCase = getTestSuiteUseCase;
        this.getEnvironmentUseCase = getEnvironmentUseCase;
        this.metrics = metrics;
    }

    @Transactional
    public void fire(Schedule schedule) {
        if (!targetValid(schedule)) {
            scheduleRepository.abandon(schedule.id(), schedule.orgId(),
                "run target no longer resolves for this org", Instant.now());
            metrics.scheduleFire("abandoned");
            log.warn("Schedule {} abandoned — target no longer valid", schedule.id());
            return;
        }

        Instant now = Instant.now();
        Slot slot = decideSlot(schedule, now);

        if (slot.skip()) {
            scheduleRepository.advanceNextFireAt(schedule.id(), schedule.orgId(),
                cron.next(schedule.cronExpression(), schedule.timeZone(), now), now);
            metrics.scheduleFire("skipped_missed");
            return;
        }

        boolean inserted = fireLedger.tryInsert(schedule.orgId(), schedule.id(), slot.instant());
        if (!inserted) {
            advanceAfterFire(schedule, now);
            metrics.scheduleFire("deduped");
            return;
        }

        var result = enqueueRunUseCase.enqueue(new EnqueueRunCommand(
            schedule.orgId(), schedule.projectId(), schedule.suiteId(), schedule.environmentId(),
            schedule.createdBy(), schedule.priority(), RunSource.SCHEDULE, schedule.id()));
        fireLedger.attachRun(schedule.id(), slot.instant(), result.runId());
        advanceAfterFire(schedule, now);
        metrics.scheduleFire(slot.caughtUp() ? "caught_up" : "fired");
        log.info("Schedule {} fired slot {} -> run {}", schedule.id(), slot.instant(), result.runId());
    }

    private void advanceAfterFire(Schedule s, Instant now) {
        if (s.kind() == ScheduleKind.ONE_TIME) {
            scheduleRepository.markOneTimeFired(s.id(), s.orgId(), now);
        } else {
            scheduleRepository.advanceNextFireAt(s.id(), s.orgId(),
                cron.next(s.cronExpression(), s.timeZone(), now), now);
        }
    }

    /** ONE_TIME -> slot = fireAt. RECURRING on-time -> slot = nextFireAt.
     *  RECURRING catch-up (the occurrence AFTER the stored one is also <= now):
     *  SKIP_MISSED -> skip; FIRE_ONCE -> exactly one make-up at previousOccurrence(now). */
    private Slot decideSlot(Schedule s, Instant now) {
        if (s.kind() == ScheduleKind.ONE_TIME) {
            return new Slot(s.fireAt(), false, false);
        }
        Instant afterStored = cron.next(s.cronExpression(), s.timeZone(), s.nextFireAt());
        boolean missedWindow = !afterStored.isAfter(now);
        if (!missedWindow) {
            return new Slot(s.nextFireAt(), false, false);
        }
        if (s.catchUpPolicy() == CatchUpPolicy.SKIP_MISSED) {
            return new Slot(null, true, false);
        }
        return new Slot(cron.previousOccurrence(s.cronExpression(), s.timeZone(), now), false, true);
    }

    private boolean targetValid(Schedule s) {
        try {
            var project = getProjectUseCase.getDomain(s.projectId(), s.orgId());
            var suite = getTestSuiteUseCase.getDomain(s.suiteId(), s.orgId());
            var env = getEnvironmentUseCase.getDomain(s.environmentId(), s.orgId());
            return suite.projectId().equals(project.id()) && env.projectId().equals(project.id());
        } catch (ProjectNotFoundException | TestSuiteNotFoundException | EnvironmentNotFoundException e) {
            return false;
        }
    }

    private record Slot(Instant instant, boolean skip, boolean caughtUp) {}
}
