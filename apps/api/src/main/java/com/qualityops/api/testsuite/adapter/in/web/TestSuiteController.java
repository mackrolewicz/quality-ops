package com.qualityops.api.testsuite.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.testsuite.application.port.in.CreateTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.DeleteTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestSuitesUseCase;
import com.qualityops.api.testsuite.application.port.in.UpdateTestSuiteUseCase;
import com.qualityops.api.testsuite.dto.CreateTestSuiteRequest;
import com.qualityops.api.testsuite.dto.TestSuiteResponse;
import com.qualityops.api.testsuite.dto.UpdateTestSuiteRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Test Suites", description = "Test suite catalog within a project")
public class TestSuiteController {

    private final CreateTestSuiteUseCase createTestSuiteUseCase;
    private final ListTestSuitesUseCase listTestSuitesUseCase;
    private final GetTestSuiteUseCase getTestSuiteUseCase;
    private final UpdateTestSuiteUseCase updateTestSuiteUseCase;
    private final DeleteTestSuiteUseCase deleteTestSuiteUseCase;

    public TestSuiteController(CreateTestSuiteUseCase createTestSuiteUseCase,
                               ListTestSuitesUseCase listTestSuitesUseCase,
                               GetTestSuiteUseCase getTestSuiteUseCase,
                               UpdateTestSuiteUseCase updateTestSuiteUseCase,
                               DeleteTestSuiteUseCase deleteTestSuiteUseCase) {
        this.createTestSuiteUseCase = createTestSuiteUseCase;
        this.listTestSuitesUseCase = listTestSuitesUseCase;
        this.getTestSuiteUseCase = getTestSuiteUseCase;
        this.updateTestSuiteUseCase = updateTestSuiteUseCase;
        this.deleteTestSuiteUseCase = deleteTestSuiteUseCase;
    }

    @GetMapping("/api/v1/projects/{projectId}/suites")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List test suites for a project")
    public ApiResponse<?> list(@PathVariable UUID projectId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listTestSuitesUseCase.list(projectId, user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @PostMapping("/api/v1/projects/{projectId}/suites")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Create a test suite within a project")
    public ApiResponse<TestSuiteResponse> create(@PathVariable UUID projectId,
                                                  @Valid @RequestBody CreateTestSuiteRequest request,
                                                  @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(
            createTestSuiteUseCase.create(projectId, request, user.orgId()));
    }

    @GetMapping("/api/v1/suites/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get a test suite by id")
    public ApiResponse<TestSuiteResponse> get(@PathVariable UUID id,
                                              @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getTestSuiteUseCase.get(id, user.orgId()));
    }

    @PutMapping("/api/v1/suites/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Update a test suite")
    public ApiResponse<TestSuiteResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateTestSuiteRequest request,
                                                 @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(updateTestSuiteUseCase.update(id, request, user.orgId()));
    }

    @DeleteMapping("/api/v1/suites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Soft-delete a test suite")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        deleteTestSuiteUseCase.delete(id, user.orgId());
    }
}
