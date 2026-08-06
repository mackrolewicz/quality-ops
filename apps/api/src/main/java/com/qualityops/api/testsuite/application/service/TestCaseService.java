package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.application.port.in.CreateTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.DeleteTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesUseCase;
import com.qualityops.api.testsuite.application.port.in.UpdateTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.TestCaseResponse;
import com.qualityops.api.testsuite.dto.UpdateTestCaseRequest;
import com.qualityops.api.testsuite.exception.TestCaseNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TestCaseService implements CreateTestCaseUseCase, ListTestCasesUseCase,
    ListTestCasesForSuiteUseCase, GetTestCaseUseCase, UpdateTestCaseUseCase, DeleteTestCaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);

    private final TestCaseRepository testCaseRepository;
    private final GetTestSuiteUseCase getTestSuiteUseCase;

    public TestCaseService(TestCaseRepository testCaseRepository, GetTestSuiteUseCase getTestSuiteUseCase) {
        this.testCaseRepository = testCaseRepository;
        this.getTestSuiteUseCase = getTestSuiteUseCase;
    }

    @Override
    public TestCaseResponse create(UUID suiteId, CreateTestCaseRequest request, UUID orgId) {
        getTestSuiteUseCase.getDomain(suiteId, orgId);
        int orderIndex = request.orderIndex() != null
            ? request.orderIndex()
            : testCaseRepository.findMaxOrderIndexBySuiteId(suiteId, orgId).map(m -> m + 1).orElse(0);
        var now = Instant.now();
        var testCase = new TestCase(
            UUID.randomUUID(),
            orgId,
            suiteId,
            request.name(),
            request.description(),
            orderIndex,
            now,
            now,
            null
        );
        var saved = testCaseRepository.save(testCase);
        log.info("Created test case {} for suite {} in org {}", saved.id(), suiteId, orgId);
        return TestCaseResponse.from(saved);
    }

    @Override
    public PageResult<TestCaseResponse> list(UUID suiteId, UUID orgId, int page, int size) {
        getTestSuiteUseCase.getDomain(suiteId, orgId);
        var result = testCaseRepository.findAllBySuiteIdAndOrgId(suiteId, orgId, page, size);
        return new PageResult<>(
            result.items().stream().map(TestCaseResponse::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @Override
    public List<TestCase> listAllForSuite(UUID suiteId, UUID orgId) {
        return testCaseRepository.findAllBySuiteIdAndOrgIdOrderByOrderIndex(suiteId, orgId);
    }

    @Override
    public TestCaseResponse get(UUID id, UUID orgId) {
        return TestCaseResponse.from(getDomain(id, orgId));
    }

    @Override
    public TestCase getDomain(UUID id, UUID orgId) {
        return testCaseRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new TestCaseNotFoundException("Test case not found: " + id));
    }

    @Override
    public TestCaseResponse update(UUID id, UpdateTestCaseRequest request, UUID orgId) {
        var existing = getDomain(id, orgId);
        var updated = new TestCase(
            existing.id(),
            existing.orgId(),
            existing.suiteId(),
            request.name(),
            request.description(),
            request.orderIndex(),
            existing.createdAt(),
            Instant.now(),
            existing.deletedAt()
        );
        var saved = testCaseRepository.save(updated);
        return TestCaseResponse.from(saved);
    }

    @Override
    public void delete(UUID id, UUID orgId) {
        getDomain(id, orgId);
        testCaseRepository.softDelete(id, orgId, Instant.now());
        log.info("Deleted test case {} in org {}", id, orgId);
    }
}
