package com.qualityops.api.identity.application.port.in;

import com.qualityops.api.identity.dto.LoginRequest;
import com.qualityops.api.identity.dto.LoginResponse;

public interface LoginUseCase {
    LoginResponse login(LoginRequest request);
}
