package com.qualityops.api.common;

/** Domain-level 409. Mirrors {@link NotFoundException}. */
public abstract class ConflictException extends RuntimeException {

    private final String code;

    protected ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
