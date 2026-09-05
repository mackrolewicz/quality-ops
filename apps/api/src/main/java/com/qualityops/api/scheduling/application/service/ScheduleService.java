package com.qualityops.api.scheduling.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.exception.ProjectNotFoundException;
import com.qualityops.api.scheduling.application.port.in.ScheduleUseCases;
import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.domain.CatchUpPolicy;
import com.qualityops.api.scheduling.domain.Schedule;
import com.qualityops.api.scheduling.domain.ScheduleKind;
import com.qualityops.api.scheduling.dto.CreateScheduleRequest;
import com.qualityops.api.scheduling.dto.NextFiresResponse;
import com.qualityops.api.scheduling.dto.ScheduleResponse;
import com.qualityops.api.scheduling.dto.UpdateScheduleRequest;
import com.qualityops.api.scheduling.exception.ScheduleNotFoundException;
import com.qualityops.api.scheduling.exception.ScheduleTargetNotFoundException;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ScheduleService implements ScheduleUseCases {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleRepository scheduleRepository;
    private final GetProjectUseCase getProjectUseCase;
    private final GetTestSuiteUseCase getTestSuiteUseCase;
    private final GetEnvironmentUseCase getEnvironmentUseCase;
    private final CronCalculator cron;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           GetProjectUseCase getProjectUseCase,
                           GetTestSuiteUseCase getTestSuiteUseCase,
                           GetEnvironmentUseCase getEnvironmentUseCase,
                           CronCalculator cron) {
        this.scheduleRepository = scheduleRepository;
        this.getProjectUseCase = getProjectUseCase;
        this.getTestSuiteUseCase = getTestSuiteUseCase;
        this.getEnvironmentUseCase = getEnvironmentUseCase;
        this.cron = cron;
    }

    @Override
    public ScheduleResponse create(UUID projectId, CreateScheduleRequest r, UUID orgId, UUID createdBy) {
        validateTarget(orgId, projectId, r.suiteId(), r.environmentId());
        var now = Instant.now();
        var kind = ScheduleKind.valueOf(r.kind());
        var schedule = new Schedule(null, orgId, projectId, r.suiteId(), r.environmentId(), r.name(),
            kind, r.cronExpression(), r.timeZone(), r.fireAt(),
            RunPriority.fromNullable(r.priority()), catchUp(r.catchUpPolicy()),
            true, computeNextFireAt(kind, true, r.cronExpression(), r.timeZone(), r.fireAt(), now),
            null, null, null, createdBy, now, now);
        var saved = scheduleRepository.save(schedule);
        log.info("Created schedule {} ({}) for project {}", saved.id(), kind, projectId);
        return ScheduleResponse.from(saved);
    }

    @Override
    public ScheduleResponse update(UUID id, UpdateScheduleRequest r, UUID orgId) {
        var existing = load(id, orgId);
        var now = Instant.now();
        var kind = ScheduleKind.valueOf(r.kind());
        var updated = new Schedule(existing.id(), orgId, existing.projectId(), existing.suiteId(),
            existing.environmentId(), r.name(), kind, r.cronExpression(), r.timeZone(), r.fireAt(),
            RunPriority.fromNullable(r.priority()), catchUp(r.catchUpPolicy()), existing.enabled(),
            computeNextFireAt(kind, existing.enabled(), r.cronExpression(), r.timeZone(), r.fireAt(), now),
            existing.lastFiredAt(), null, null, existing.createdBy(), existing.createdAt(), now);
        return ScheduleResponse.from(scheduleRepository.save(updated));
    }

    @Override
    @Transactional(readOnly = true)
    public ScheduleResponse get(UUID id, UUID orgId) {
        return ScheduleResponse.from(load(id, orgId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ScheduleResponse> list(UUID projectId, UUID orgId, int page, int size) {
        var result = scheduleRepository.findByOrgAndProject(orgId, projectId, page, size);
        return new PageResult<>(result.items().stream().map(ScheduleResponse::from).toList(),
            result.page(), result.size(), result.total());
    }

    @Override
    public void delete(UUID id, UUID orgId) {
        load(id, orgId);
        scheduleRepository.deleteByIdAndOrgId(id, orgId);
    }

    @Override
    public ScheduleResponse pause(UUID id, UUID orgId) {
        var s = load(id, orgId);
        var paused = withEnabled(s, false, null, Instant.now());
        return ScheduleResponse.from(scheduleRepository.save(paused));
    }

    @Override
    public ScheduleResponse resume(UUID id, UUID orgId) {
        var s = load(id, orgId);
        var next = computeNextFireAt(s.kind(), true, s.cronExpression(), s.timeZone(), s.fireAt(),
            Instant.now());
        var resumed = withEnabled(s, true, next, Instant.now());
        return ScheduleResponse.from(scheduleRepository.save(resumed));
    }

    @Override
    @Transactional(readOnly = true)
    public NextFiresResponse previewNextFires(UUID id, UUID orgId, int count) {
        var s = load(id, orgId);
        int clamped = Math.max(1, Math.min(count, 50));
        List<Instant> fires = s.kind() == ScheduleKind.RECURRING
            ? cron.nextN(s.cronExpression(), s.timeZone(), Instant.now(), clamped)
            : (s.fireAt() != null && s.fireAt().isAfter(Instant.now())) ? List.of(s.fireAt()) : List.of();
        return new NextFiresResponse(id, fires);
    }

    private Schedule load(UUID id, UUID orgId) {
        return scheduleRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + id));
    }

    private Instant computeNextFireAt(ScheduleKind kind, boolean enabled, String cronExpr,
                                     String tz, Instant fireAt, Instant from) {
        if (!enabled) {
            return null;
        }
        return kind == ScheduleKind.ONE_TIME ? fireAt : cron.next(cronExpr, tz, from);
    }

    private static CatchUpPolicy catchUp(String raw) {
        return raw == null || raw.isBlank() ? CatchUpPolicy.SKIP_MISSED : CatchUpPolicy.valueOf(raw);
    }

    private static Schedule withEnabled(Schedule s, boolean enabled, Instant nextFireAt, Instant now) {
        return new Schedule(s.id(), s.orgId(), s.projectId(), s.suiteId(), s.environmentId(), s.name(),
            s.kind(), s.cronExpression(), s.timeZone(), s.fireAt(), s.priority(), s.catchUpPolicy(),
            enabled, nextFireAt, s.lastFiredAt(), s.lastError(), s.lastErrorAt(), s.createdBy(),
            s.createdAt(), now);
    }

    private void validateTarget(UUID orgId, UUID projectId, UUID suiteId, UUID environmentId) {
        try {
            var project = getProjectUseCase.getDomain(projectId, orgId);
            var suite = getTestSuiteUseCase.getDomain(suiteId, orgId);
            var env = getEnvironmentUseCase.getDomain(environmentId, orgId);
            if (!suite.projectId().equals(project.id()) || !env.projectId().equals(project.id())) {
                throw new ScheduleTargetNotFoundException(
                    "suite/environment do not belong to project " + projectId);
            }
        } catch (ProjectNotFoundException | TestSuiteNotFoundException | EnvironmentNotFoundException e) {
            throw new ScheduleTargetNotFoundException(e.getMessage());
        }
    }
}
