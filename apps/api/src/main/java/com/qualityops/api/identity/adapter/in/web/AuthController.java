package com.qualityops.api.identity.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.identity.application.port.in.LoginUseCase;
import com.qualityops.api.identity.application.port.in.LogoutUseCase;
import com.qualityops.api.identity.application.port.in.RefreshTokenUseCase;
import com.qualityops.api.identity.dto.LoginRequest;
import com.qualityops.api.identity.dto.LoginResponse;
import com.qualityops.api.identity.dto.RefreshRequest;
import com.qualityops.api.identity.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "JWT login, token refresh, and logout")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    public AuthController(LoginUseCase loginUseCase,
                          RefreshTokenUseCase refreshTokenUseCase,
                          LogoutUseCase logoutUseCase) {
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Authenticate with email + password — returns access + refresh tokens")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(loginUseCase.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a valid refresh token for a new access + refresh token pair")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.success(refreshTokenUseCase.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the refresh token (idempotent)")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        logoutUseCase.logout(request.refreshToken());
    }
}
