package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.audit.annotation.Audited;
import com.qualityops.api.audit.domain.AuditAction;
import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.testsuite.application.port.in.CreateTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.DeleteTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestSuitesUseCase;
import com.qualityops.api.testsuite.application.port.in.UpdateTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.out.TestSuiteRepository;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.dto.CreateTestSuiteRequest;
import com.qualityops.api.testsuite.dto.TestSuiteResponse;
import com.qualityops.api.testsuite.dto.UpdateTestSuiteRequest;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class TestSuiteService implements CreateTestSuiteUseCase, ListTestSuitesUseCase,
    GetTestSuiteUseCase, UpdateTestSuiteUseCase, DeleteTestSuiteUseCase {

    private static final Logger log = LoggerFactory.getLogger(TestSuiteService.class);

    private final TestSuiteRepository testSuiteRepository;
    private final GetProjectUseCase getProjectUseCase;

    public TestSuiteService(TestSuiteRepository testSuiteRepository, GetProjectUseCase getProjectUseCase) {
        this.testSuiteRepository = testSuiteRepository;
        this.getProjectUseCase = getProjectUseCase;
    }

    @Override
    public TestSuiteResponse create(UUID projectId, CreateTestSuiteRequest request, UUID orgId) {
        getProjectUseCase.getDomain(projectId, orgId);
        var now = Instant.now();
        var suite = new TestSuite(
            UUID.randomUUID(),
            orgId,
            projectId,
            request.name(),
            request.description(),
            request.type(),
            now,
            now,
            null
        );
        var saved = testSuiteRepository.save(suite);
        log.info("Created test suite {} for project {} in org {}", saved.id(), projectId, orgId);
        return TestSuiteResponse.from(saved);
    }

    @Override
    public PageResult<TestSuiteResponse> list(UUID projectId, UUID orgId, int page, int size) {
        getProjectUseCase.getDomain(projectId, orgId);
        var result = testSuiteRepository.findAllByProjectIdAndOrgId(projectId, orgId, page, size);
        return new PageResult<>(
            result.items().stream().map(TestSuiteResponse::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @Override
    public TestSuiteResponse get(UUID id, UUID orgId) {
        return TestSuiteResponse.from(getDomain(id, orgId));
    }

    @Override
    public TestSuite getDomain(UUID id, UUID orgId) {
        return testSuiteRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new TestSuiteNotFoundException("Test suite not found: " + id));
    }

    @Override
    public TestSuiteResponse update(UUID id, UpdateTestSuiteRequest request, UUID orgId) {
        var existing = getDomain(id, orgId);
        var updated = new TestSuite(
            existing.id(),
            existing.orgId(),
            existing.projectId(),
            request.name(),
            request.description(),
            request.type(),
            existing.createdAt(),
            Instant.now(),
            existing.deletedAt()
        );
        var saved = testSuiteRepository.save(updated);
        return TestSuiteResponse.from(saved);
    }

    @Override
    @Audited(action = AuditAction.TEST_SUITE_DELETE, targetType = "test_suite")
    public void delete(UUID id, UUID orgId) {
        getDomain(id, orgId);
        testSuiteRepository.softDelete(id, orgId, Instant.now());
        log.info("Deleted test suite {} in org {}", id, orgId);
    }
}
