package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.UpdateTestCaseRequest;
import com.qualityops.api.testsuite.exception.TestCaseNotFoundException;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private GetTestSuiteUseCase getTestSuiteUseCase;

    private TestCaseService testCaseService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testCaseService = new TestCaseService(testCaseRepository, getTestSuiteUseCase);
    }

    @Test
    void create_suiteInDifferentOrgOrMissing_throwsTestSuiteNotFoundException() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId))
            .thenThrow(new TestSuiteNotFoundException("Test suite not found: " + suiteId));
        var request = new CreateTestCaseRequest("Case 1", "desc", null);

        assertThatThrownBy(() -> testCaseService.create(suiteId, request, orgId))
            .isInstanceOf(TestSuiteNotFoundException.class);

        verify(testCaseRepository, never()).save(any(TestCase.class));
    }

    @Test
    void create_nullOrderIndex_computesNextFromMax() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.findMaxOrderIndexBySuiteId(suiteId, orgId)).thenReturn(Optional.of(4));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", null);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().orderIndex()).isEqualTo(5);
    }

    @Test
    void create_nullOrderIndexNoExistingCases_defaultsToZero() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.findMaxOrderIndexBySuiteId(suiteId, orgId)).thenReturn(Optional.empty());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", null);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().orderIndex()).isEqualTo(0);
    }

    @Test
    void create_explicitOrderIndex_usesAsGiven() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", 7);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().orderIndex()).isEqualTo(7);
        verify(testCaseRepository, never()).findMaxOrderIndexBySuiteId(any(), any());
    }

    @Test
    void get_wrongOrgOrMissing_throws() {
        when(testCaseRepository.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testCaseService.get(caseId, orgId))
            .isInstanceOf(TestCaseNotFoundException.class);
    }

    @Test
    void update_notFound_throws() {
        when(testCaseRepository.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.empty());
        var request = new UpdateTestCaseRequest("New name", "new desc", 2);

        assertThatThrownBy(() -> testCaseService.update(caseId, request, orgId))
            .isInstanceOf(TestCaseNotFoundException.class);
    }

    @Test
    void delete_notFound_throws() {
        when(testCaseRepository.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testCaseService.delete(caseId, orgId))
            .isInstanceOf(TestCaseNotFoundException.class);

        verify(testCaseRepository, never()).softDelete(any(), any(), any());
    }

    @Test
    void delete_found_callsSoftDelete() {
        var testCase = existingCase();
        when(testCaseRepository.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(testCase));

        testCaseService.delete(caseId, orgId);

        verify(testCaseRepository, times(1)).softDelete(eq(caseId), eq(orgId), any(Instant.class));
    }

    @Test
    void listAllForSuite_delegatesToRepositoryOrderedQuery() {
        var testCase = existingCase();
        when(testCaseRepository.findAllBySuiteIdAndOrgIdOrderByOrderIndex(suiteId, orgId))
            .thenReturn(List.of(testCase));

        var result = testCaseService.listAllForSuite(suiteId, orgId);

        assertThat(result).containsExactly(testCase);
    }

    private TestSuite existingSuite() {
        var now = Instant.now();
        return new TestSuite(suiteId, orgId, UUID.randomUUID(), "Smoke", "desc", SuiteType.API, now, now, null);
    }

    private TestCase existingCase() {
        var now = Instant.now();
        return new TestCase(caseId, orgId, suiteId, "Case 1", "desc", 0, now, now, null);
    }
}
