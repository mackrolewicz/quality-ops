package com.qualityops.api.testsuite.exception;

import com.qualityops.api.common.NotFoundException;

public class TestCaseNotFoundException extends NotFoundException {
    public TestCaseNotFoundException(String message) {
        super("TEST_CASE_NOT_FOUND", message);
    }
}
