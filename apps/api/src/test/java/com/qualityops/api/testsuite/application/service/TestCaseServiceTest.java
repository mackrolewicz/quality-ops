package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.ApiRequestSpec;
import com.qualityops.api.testsuite.domain.BrowserTestSpec;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.dto.ApiRequestPayload;
import com.qualityops.api.testsuite.dto.BrowserTestPayload;
import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.UpdateTestCaseRequest;
import com.qualityops.api.testsuite.exception.TestCaseNotFoundException;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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
        var request = new CreateTestCaseRequest("Case 1", "desc", null, null, null);

        assertThatThrownBy(() -> testCaseService.create(suiteId, request, orgId))
            .isInstanceOf(TestSuiteNotFoundException.class);

        verify(testCaseRepository, never()).save(any(TestCase.class));
    }

    @Test
    void create_nullOrderIndex_computesNextFromMax() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.findMaxOrderIndexBySuiteId(suiteId, orgId)).thenReturn(Optional.of(4));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", null, null, null);

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
        var request = new CreateTestCaseRequest("Case 1", "desc", null, null, null);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().orderIndex()).isEqualTo(0);
    }

    @Test
    void create_explicitOrderIndex_usesAsGiven() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", 7, null, null);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().orderIndex()).isEqualTo(7);
        verify(testCaseRepository, never()).findMaxOrderIndexBySuiteId(any(), any());
    }

    @Test
    void create_withApiRequestPayload_mapsIntoDomainSpec() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, apiRequestPayload(), null);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        var spec = captor.getValue().apiRequest();
        assertThat(spec).isNotNull();
        assertThat(spec.method()).isEqualTo("POST");
        assertThat(spec.url()).isEqualTo("https://api.example.test/login");
        assertThat(spec.headers()).hasSize(1);
        assertThat(spec.assertions()).hasSize(1);
    }

    @Test
    void create_withNullApiRequest_savesNullSpec() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, null);

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().apiRequest()).isNull();
    }

    @Test
    void create_withBrowserTestPayload_mapsIntoDomainSpec() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, browserTestPayload());

        testCaseService.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        var spec = captor.getValue().browserTest();
        assertThat(spec).isNotNull();
        assertThat(spec.startUrl()).isEqualTo("https://app.example.test/login");
        assertThat(spec.steps()).hasSize(2);
        assertThat(spec.assertions()).hasSize(1);
    }

    @Test
    void create_withBothApiRequestAndBrowserTest_throwsIllegalArgumentException() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, apiRequestPayload(), browserTestPayload());

        assertThatThrownBy(() -> testCaseService.create(suiteId, request, orgId))
            .isInstanceOf(IllegalArgumentException.class);

        verify(testCaseRepository, never()).save(any(TestCase.class));
    }

    @Test
    void update_withNullApiRequest_clearsSpec() {
        var existing = new TestCase(caseId, orgId, suiteId, "Case 1", "desc", 0,
            new ApiRequestSpec("GET", "https://api.example.test/x", List.of(), null, 200, null, null, List.of()),
            null, Instant.now(), Instant.now(), null);
        when(testCaseRepository.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(existing));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new UpdateTestCaseRequest("Case 1", "desc", 0, null, null);

        testCaseService.update(caseId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().apiRequest()).isNull();
    }

    @Test
    void update_withNullBrowserTest_clearsSpec() {
        var existing = new TestCase(caseId, orgId, suiteId, "Case 1", "desc", 0,
            null,
            new BrowserTestSpec("https://app.example.test/login",
                List.of(new BrowserTestSpec.BrowserStepSpec("NAVIGATE", null, "https://app.example.test/login", null)),
                List.of(new BrowserTestSpec.BrowserAssertionSpec("URL_CONTAINS", null, "/login")),
                null, null, null),
            Instant.now(), Instant.now(), null);
        when(testCaseRepository.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(existing));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new UpdateTestCaseRequest("Case 1", "desc", 0, null, null);

        testCaseService.update(caseId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().browserTest()).isNull();
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
        var request = new UpdateTestCaseRequest("New name", "new desc", 2, null, null);

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
        return new TestCase(caseId, orgId, suiteId, "Case 1", "desc", 0, null, null, now, now, null);
    }

    // --- request-payload validation (folded in from the web slice: no MockMvc
    //     infrastructure exists in this module, so the Bean Validation graph is
    //     exercised directly against the request record). ---

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void create_invalidMethod_failsValidationOnMethodField() {
        var payload = new ApiRequestPayload("FETCH", "https://api.example.test/x",
            List.of(), null, 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        var violations = VALIDATOR.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("apiRequest.method"));
    }

    @Test
    void create_bodyTooLarge_failsValidation() {
        var payload = new ApiRequestPayload("POST", "https://api.example.test/x",
            List.of(), "x".repeat(64001), 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        var violations = VALIDATOR.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("apiRequest.body"));
    }

    @Test
    void create_expectedStatusOutOfRange_failsValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(), null, 42, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        var violations = VALIDATOR.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("apiRequest.expectedStatus"));
    }

    @Test
    void create_validApiRequest_passesValidation() {
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, apiRequestPayload(), null);

        var violations = VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void create_headerEqualsAssertionWithoutTarget_failsValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(), null, 200, null, null,
            List.of(new ApiRequestPayload.AssertionPayload("HEADER_EQUALS", null, "application/json")));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        var violations = VALIDATOR.validate(request);

        assertThat(violations).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("apiRequest.assertions"));
    }

    @Test
    void create_jsonPathAssertionWithBlankTarget_failsValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(), null, 200, null, null,
            List.of(new ApiRequestPayload.AssertionPayload("JSON_PATH_EQUALS", "   ", "42")));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("apiRequest.assertions"));
    }

    @Test
    void create_statusEqualsAssertionWithoutTarget_passesValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(), null, 200, null, null,
            List.of(new ApiRequestPayload.AssertionPayload("STATUS_EQUALS", null, "200")));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void create_roleSelectorWithoutRoleName_failsValidation() {
        var badStep = new BrowserTestPayload.BrowserStepPayload("CLICK",
            new BrowserTestPayload.SelectorPayload("ROLE", null, null, null), null, null);
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(badStep),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            null, null, null);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("browserTest.steps"));
    }

    @Test
    void create_fillStepWithoutValue_failsValidation() {
        var badStep = new BrowserTestPayload.BrowserStepPayload("FILL",
            new BrowserTestPayload.SelectorPayload("LABEL", "Email", null, null), null, null);
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(badStep),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            null, null, null);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("browserTest.steps"));
    }

    @Test
    void create_navigateStepWithTarget_failsValidation() {
        var badStep = new BrowserTestPayload.BrowserStepPayload("NAVIGATE",
            new BrowserTestPayload.SelectorPayload("CSS", "#x", null, null), "https://app.example.test/login", null);
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(badStep),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            null, null, null);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("browserTest.steps"));
    }

    @Test
    void create_elementStateAssertionWithBogusExpected_failsValidation() {
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(new BrowserTestPayload.BrowserStepPayload("NAVIGATE", null, "https://app.example.test/login", null)),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("ELEMENT_STATE",
                new BrowserTestPayload.SelectorPayload("CSS", "#go", null, null), "sparkling")),
            null, null, null);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("browserTest.assertions"));
    }

    @Test
    void create_emptySteps_failsValidation() {
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            null, null, null);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().equals("browserTest.steps"));
    }

    @Test
    void create_bothSpecs_failsRecordLevelAssertTrue() {
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, apiRequestPayload(), browserTestPayload());

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().equals("specMutuallyExclusive"));
    }

    @Test
    void create_validBrowserTest_passesValidation() {
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, browserTestPayload());

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void create_headerWithBothValueAndSecretRef_failsValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(new ApiRequestPayload.HeaderPayload("Authorization", "Bearer x", "API_TOKEN")),
            null, 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("apiRequest.headers"));
    }

    @Test
    void create_headerWithNeitherValueNorSecretRef_failsValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(new ApiRequestPayload.HeaderPayload("Authorization", null, null)),
            null, 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("apiRequest.headers"));
    }

    @Test
    void create_headerWithSecretRefOnly_passesValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(new ApiRequestPayload.HeaderPayload("Authorization", null, "API_TOKEN")),
            null, 200, null, null,
            List.of(new ApiRequestPayload.AssertionPayload("STATUS_EQUALS", null, "200")));
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void create_secretRefWithLowercaseKey_failsPatternValidation() {
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(new ApiRequestPayload.HeaderPayload("Authorization", null, "api-token")),
            null, 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("apiRequest.headers"));
    }

    @Test
    void create_fillStepWithSecretRefOnly_passesValidation() {
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(new BrowserTestPayload.BrowserStepPayload("FILL",
                new BrowserTestPayload.SelectorPayload("LABEL", "Password", null, null),
                null, null, "DEMO_PASSWORD")),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            60000, 15000, 30000);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void create_fillStepWithBothValueAndSecretRef_failsValidation() {
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(new BrowserTestPayload.BrowserStepPayload("FILL",
                new BrowserTestPayload.SelectorPayload("LABEL", "Password", null, null),
                "hunter2", null, "DEMO_PASSWORD")),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            60000, 15000, 30000);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        assertThat(VALIDATOR.validate(request)).anyMatch(v ->
            v.getPropertyPath().toString().startsWith("browserTest.steps"));
    }

    @Test
    void create_headerSecretRefKey_isPersistedAndRoundTripsToResponse() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var payload = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(new ApiRequestPayload.HeaderPayload("Authorization", null, "API_TOKEN")),
            null, 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, payload, null);

        var response = testCaseService.create(suiteId, request, orgId);

        var savedCaptor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(savedCaptor.capture());
        var savedHeader = savedCaptor.getValue().apiRequest().headers().get(0);
        assertThat(savedHeader.secretRef()).isEqualTo("API_TOKEN");
        assertThat(savedHeader.value()).isNull();
        assertThat(response.apiRequest().headers().get(0).secretRef()).isEqualTo("API_TOKEN");
    }

    @Test
    void create_fillStepSecretRefKey_isPersisted() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var payload = new BrowserTestPayload("https://app.example.test/login",
            List.of(new BrowserTestPayload.BrowserStepPayload("FILL",
                new BrowserTestPayload.SelectorPayload("LABEL", "Password", null, null),
                null, null, "DEMO_PASSWORD")),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            60000, 15000, 30000);
        var request = new CreateTestCaseRequest("Case 1", "desc", 0, null, payload);

        testCaseService.create(suiteId, request, orgId);

        var savedCaptor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().browserTest().steps().get(0).secretRef()).isEqualTo("DEMO_PASSWORD");
    }

    private ApiRequestPayload apiRequestPayload() {
        return new ApiRequestPayload("POST", "https://api.example.test/login",
            List.of(new ApiRequestPayload.HeaderPayload("Accept", "application/json")),
            "{\"u\":1}", 200, 5000, 65536L,
            List.of(new ApiRequestPayload.AssertionPayload("STATUS_EQUALS", "", "200")));
    }

    private BrowserTestPayload browserTestPayload() {
        return new BrowserTestPayload("https://app.example.test/login",
            List.of(
                new BrowserTestPayload.BrowserStepPayload("NAVIGATE", null, "https://app.example.test/login", null),
                new BrowserTestPayload.BrowserStepPayload("CLICK",
                    new BrowserTestPayload.SelectorPayload("ROLE", null, "button", "Go"), null, null)),
            List.of(new BrowserTestPayload.BrowserAssertionPayload("URL_CONTAINS", null, "/home")),
            60000, 15000, 30000);
    }
}
