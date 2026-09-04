package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.result.domain.TestResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class TestResultRepositoryAdapter implements TestResultRepository {

    private final TestResultJpaRepository jpa;

    TestResultRepositoryAdapter(TestResultJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void upsert(TestResult r) {
        jpa.upsertCaseResult(r.orgId(), r.runId(), r.testCaseId(), r.status().name(),
            r.durationMs(), r.errorMessage(), r.retryCount(), r.attemptEpoch());
    }

    @Override
    public PageResult<TestResult> findAllByRunIdAndOrgId(UUID runId, UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByRunIdAndOrgId(runId, orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(TestResultRepositoryAdapter::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    private static TestResult toDomain(TestResultEntity entity) {
        return new TestResult(
            entity.getId(),
            entity.getOrgId(),
            entity.getRunId(),
            entity.getTestCaseId(),
            entity.getStatus(),
            entity.getDurationMs(),
            entity.getErrorMessage(),
            entity.getRetryCount(),
            entity.getAttemptEpoch(),
            entity.getCreatedAt()
        );
    }
}
