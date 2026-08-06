package com.qualityops.api.identity.application.port.in;

public interface LogoutUseCase {
    void logout(String rawRefreshToken);
}
