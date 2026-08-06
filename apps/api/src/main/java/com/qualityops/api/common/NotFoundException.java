package com.qualityops.api.common;

public abstract class NotFoundException extends RuntimeException {

    private final String code;

    protected NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
