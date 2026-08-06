package com.qualityops.api.testsuite.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.TestCase;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class TestCaseRepositoryAdapter implements TestCaseRepository {

    private final TestCaseJpaRepository jpa;

    TestCaseRepositoryAdapter(TestCaseJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public TestCase save(TestCase testCase) {
        return jpa.save(TestCaseEntity.fromDomain(testCase)).toDomain();
    }

    @Override
    public Optional<TestCase> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId).map(TestCaseEntity::toDomain);
    }

    @Override
    public PageResult<TestCase> findAllBySuiteIdAndOrgId(UUID suiteId, UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllBySuiteIdAndOrgIdAndDeletedAtIsNull(suiteId, orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(TestCaseEntity::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    public List<TestCase> findAllBySuiteIdAndOrgIdOrderByOrderIndex(UUID suiteId, UUID orgId) {
        return jpa.findAllBySuiteIdAndOrgIdAndDeletedAtIsNullOrderByOrderIndexAsc(suiteId, orgId).stream()
            .map(TestCaseEntity::toDomain)
            .toList();
    }

    @Override
    public Optional<Integer> findMaxOrderIndexBySuiteId(UUID suiteId, UUID orgId) {
        return jpa.findMaxOrderIndexBySuiteId(suiteId, orgId);
    }

    @Override
    public void softDelete(UUID id, UUID orgId, Instant deletedAt) {
        jpa.softDelete(id, orgId, deletedAt);
    }
}
