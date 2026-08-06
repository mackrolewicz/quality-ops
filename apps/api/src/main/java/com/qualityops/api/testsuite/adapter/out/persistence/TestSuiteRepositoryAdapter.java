package com.qualityops.api.testsuite.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.application.port.out.TestSuiteRepository;
import com.qualityops.api.testsuite.domain.TestSuite;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class TestSuiteRepositoryAdapter implements TestSuiteRepository {

    private final TestSuiteJpaRepository jpa;

    TestSuiteRepositoryAdapter(TestSuiteJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public TestSuite save(TestSuite suite) {
        return jpa.save(TestSuiteEntity.fromDomain(suite)).toDomain();
    }

    @Override
    public Optional<TestSuite> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId).map(TestSuiteEntity::toDomain);
    }

    @Override
    public PageResult<TestSuite> findAllByProjectIdAndOrgId(UUID projectId, UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(TestSuiteEntity::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    public void softDelete(UUID id, UUID orgId, Instant deletedAt) {
        jpa.softDelete(id, orgId, deletedAt);
    }
}
