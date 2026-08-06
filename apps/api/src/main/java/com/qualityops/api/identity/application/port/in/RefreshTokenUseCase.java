package com.qualityops.api.identity.application.port.in;

import com.qualityops.api.identity.dto.TokenResponse;

public interface RefreshTokenUseCase {
    TokenResponse refresh(String rawRefreshToken);
}
