package com.qualityops.api.testsuite.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.testsuite.application.port.in.CreateTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.DeleteTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestCaseUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesUseCase;
import com.qualityops.api.testsuite.application.port.in.UpdateTestCaseUseCase;
import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.TestCaseResponse;
import com.qualityops.api.testsuite.dto.UpdateTestCaseRequest;
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
@Tag(name = "Test Cases", description = "Test cases within a test suite")
public class TestCaseController {

    private final CreateTestCaseUseCase createTestCaseUseCase;
    private final ListTestCasesUseCase listTestCasesUseCase;
    private final GetTestCaseUseCase getTestCaseUseCase;
    private final UpdateTestCaseUseCase updateTestCaseUseCase;
    private final DeleteTestCaseUseCase deleteTestCaseUseCase;

    public TestCaseController(CreateTestCaseUseCase createTestCaseUseCase,
                              ListTestCasesUseCase listTestCasesUseCase,
                              GetTestCaseUseCase getTestCaseUseCase,
                              UpdateTestCaseUseCase updateTestCaseUseCase,
                              DeleteTestCaseUseCase deleteTestCaseUseCase) {
        this.createTestCaseUseCase = createTestCaseUseCase;
        this.listTestCasesUseCase = listTestCasesUseCase;
        this.getTestCaseUseCase = getTestCaseUseCase;
        this.updateTestCaseUseCase = updateTestCaseUseCase;
        this.deleteTestCaseUseCase = deleteTestCaseUseCase;
    }

    @GetMapping("/api/v1/suites/{suiteId}/cases")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List test cases for a suite")
    public ApiResponse<?> list(@PathVariable UUID suiteId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listTestCasesUseCase.list(suiteId, user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @PostMapping("/api/v1/suites/{suiteId}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Create a test case within a suite")
    public ApiResponse<TestCaseResponse> create(@PathVariable UUID suiteId,
                                                 @Valid @RequestBody CreateTestCaseRequest request,
                                                 @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(
            createTestCaseUseCase.create(suiteId, request, user.orgId()));
    }

    @GetMapping("/api/v1/cases/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get a test case by id")
    public ApiResponse<TestCaseResponse> get(@PathVariable UUID id,
                                             @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getTestCaseUseCase.get(id, user.orgId()));
    }

    @PutMapping("/api/v1/cases/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Update a test case")
    public ApiResponse<TestCaseResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateTestCaseRequest request,
                                                @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(updateTestCaseUseCase.update(id, request, user.orgId()));
    }

    @DeleteMapping("/api/v1/cases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Soft-delete a test case")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        deleteTestCaseUseCase.delete(id, user.orgId());
    }
}
