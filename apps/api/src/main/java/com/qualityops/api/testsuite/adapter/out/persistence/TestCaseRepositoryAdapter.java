package com.qualityops.api.testsuite.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.ApiRequestSpec;
import com.qualityops.api.testsuite.domain.BrowserTestSpec;
import com.qualityops.api.testsuite.domain.RepoTestSpec;
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
    private final ObjectMapper objectMapper;

    TestCaseRepositoryAdapter(TestCaseJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public TestCase save(TestCase testCase) {
        var entity = TestCaseEntity.fromDomain(testCase, writeSpec(testCase.apiRequest()),
            writeBrowserSpec(testCase.browserTest()), writeRepoSpec(testCase.repoTest()));
        return toDomain(jpa.save(entity));
    }

    @Override
    public long countReferencingConnection(UUID orgId, UUID connectionId) {
        return jpa.countReferencingConnection(orgId, connectionId.toString());
    }

    @Override
    public Optional<TestCase> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId).map(this::toDomain);
    }

    @Override
    public PageResult<TestCase> findAllBySuiteIdAndOrgId(UUID suiteId, UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllBySuiteIdAndOrgIdAndDeletedAtIsNull(suiteId, orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(this::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    public List<TestCase> findAllBySuiteIdAndOrgIdOrderByOrderIndex(UUID suiteId, UUID orgId) {
        return jpa.findAllBySuiteIdAndOrgIdAndDeletedAtIsNullOrderByOrderIndexAsc(suiteId, orgId).stream()
            .map(this::toDomain)
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

    private TestCase toDomain(TestCaseEntity e) {
        return new TestCase(e.getId(), e.getOrgId(), e.getSuiteId(), e.getName(), e.getDescription(),
            e.getOrderIndex(), readSpec(e.getApiRequestJson()), readBrowserSpec(e.getBrowserTestJson()),
            readRepoSpec(e.getRepoTestJson()), e.getCreatedAt(), e.getUpdatedAt(), e.getDeletedAt());
    }

    private String writeSpec(ApiRequestSpec spec) {
        if (spec == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize test case api_request", ex);
        }
    }

    private ApiRequestSpec readSpec(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ApiRequestSpec.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize test case api_request", ex);
        }
    }

    private String writeBrowserSpec(BrowserTestSpec spec) {
        if (spec == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize test case browser_test", ex);
        }
    }

    private BrowserTestSpec readBrowserSpec(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, BrowserTestSpec.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize test case browser_test", ex);
        }
    }

    private String writeRepoSpec(RepoTestSpec spec) {
        if (spec == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize test case repo_test", ex);
        }
    }

    private RepoTestSpec readRepoSpec(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RepoTestSpec.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize test case repo_test", ex);
        }
    }
}
