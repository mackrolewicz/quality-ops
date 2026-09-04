package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.application.port.in.CountTestCasesReferencingConnectionUseCase;
import com.qualityops.api.testsuite.application.port.in.CreateTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.DeleteTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesUseCase;
import com.qualityops.api.testsuite.application.port.in.UpdateTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.ApiRequestSpec;
import com.qualityops.api.testsuite.domain.BrowserTestSpec;
import com.qualityops.api.testsuite.domain.RepoTestSpec;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.dto.ApiRequestPayload;
import com.qualityops.api.testsuite.dto.BrowserTestPayload;
import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.RepoTestPayload;
import com.qualityops.api.testsuite.dto.TestCaseResponse;
import com.qualityops.api.testsuite.dto.UpdateTestCaseRequest;
import com.qualityops.api.testsuite.exception.TestCaseNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Transactional
public class TestCaseService implements CreateTestCaseUseCase, ListTestCasesUseCase,
    ListTestCasesForSuiteUseCase, GetTestCaseUseCase, UpdateTestCaseUseCase, DeleteTestCaseUseCase,
    CountTestCasesReferencingConnectionUseCase {

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
        requireAtMostOneSpec(request.apiRequest(), request.browserTest(), request.repoTest());
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
            toSpec(request.apiRequest()),
            toBrowserSpec(request.browserTest()),
            toRepoSpec(request.repoTest()),
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
        requireAtMostOneSpec(request.apiRequest(), request.browserTest(), request.repoTest());
        var updated = new TestCase(
            existing.id(),
            existing.orgId(),
            existing.suiteId(),
            request.name(),
            request.description(),
            request.orderIndex(),
            toSpec(request.apiRequest()),
            toBrowserSpec(request.browserTest()),
            toRepoSpec(request.repoTest()),
            existing.createdAt(),
            Instant.now(),
            existing.deletedAt()
        );
        var saved = testCaseRepository.save(updated);
        return TestCaseResponse.from(saved);
    }

    /** Request payload -> test-suite domain spec. Null-safe; PUT with null clears. */
    private static ApiRequestSpec toSpec(ApiRequestPayload p) {
        if (p == null) {
            return null;
        }
        var headers = p.headers() == null ? List.<ApiRequestSpec.HeaderPair>of()
            : p.headers().stream()
                .map(h -> new ApiRequestSpec.HeaderPair(h.name(), h.value(), h.secretRef()))
                .toList();
        var assertions = p.assertions() == null ? List.<ApiRequestSpec.ApiAssertionSpec>of()
            : p.assertions().stream()
                .map(a -> new ApiRequestSpec.ApiAssertionSpec(a.type(), a.target(), a.expected()))
                .toList();
        return new ApiRequestSpec(p.method(), p.url(), headers, p.body(),
            p.expectedStatus(), p.timeoutMillis(), p.maxResponseBytes(), assertions);
    }

    /** Request payload -> test-suite browser spec. Null-safe; PUT with null clears. */
    private static BrowserTestSpec toBrowserSpec(BrowserTestPayload p) {
        if (p == null) {
            return null;
        }
        var steps = p.steps().stream()
            .map(s -> new BrowserTestSpec.BrowserStepSpec(
                s.action(), toSelector(s.target()), s.value(), s.key(), s.secretRef()))
            .toList();
        var assertions = p.assertions().stream()
            .map(a -> new BrowserTestSpec.BrowserAssertionSpec(
                a.type(), toSelector(a.target()), a.expected()))
            .toList();
        return new BrowserTestSpec(p.startUrl(), steps, assertions,
            p.testTimeoutMillis(), p.stepTimeoutMillis(), p.navigationTimeoutMillis());
    }

    private static BrowserTestSpec.SelectorSpec toSelector(BrowserTestPayload.SelectorPayload s) {
        return s == null ? null
            : new BrowserTestSpec.SelectorSpec(s.strategy(), s.value(), s.roleName(), s.accessibleName());
    }

    /** Request payload -> test-suite repository spec. Null-safe; PUT with null clears. */
    private static RepoTestSpec toRepoSpec(RepoTestPayload p) {
        if (p == null) {
            return null;
        }
        var env = p.environmentVars() == null ? List.<RepoTestSpec.EnvVarSpec>of()
            : p.environmentVars().stream()
                .map(e -> new RepoTestSpec.EnvVarSpec(e.name(), e.value()))
                .toList();
        var secrets = p.secretVars() == null ? List.<RepoTestSpec.SecretVarSpec>of()
            : p.secretVars().stream()
                .map(s -> new RepoTestSpec.SecretVarSpec(s.name(), s.secretRef()))
                .toList();
        return new RepoTestSpec(p.repositoryConnectionId(), p.requestedRef(), p.framework(),
            p.workingDir(), p.command(), p.reportFormat(), p.reportPaths(), p.artifactGlobs(),
            env, secrets, p.resourceProfile(), p.networkPolicy(), p.timeoutSeconds());
    }

    /** Server-side defence-in-depth for the authoring DTO's {@code @AssertTrue}. */
    private static void requireAtMostOneSpec(Object apiRequest, Object browserTest, Object repoTest) {
        if (Stream.of(apiRequest, browserTest, repoTest).filter(Objects::nonNull).count() > 1) {
            throw new IllegalArgumentException(
                "A test case may define at most one of apiRequest, browserTest, repoTest");
        }
    }

    @Override
    public long countReferencingConnection(UUID connectionId, UUID orgId) {
        return testCaseRepository.countReferencingConnection(orgId, connectionId);
    }

    @Override
    public void delete(UUID id, UUID orgId) {
        getDomain(id, orgId);
        testCaseRepository.softDelete(id, orgId, Instant.now());
        log.info("Deleted test case {} in org {}", id, orgId);
    }
}
