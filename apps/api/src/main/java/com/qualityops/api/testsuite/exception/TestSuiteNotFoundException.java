package com.qualityops.api.testsuite.exception;

import com.qualityops.api.common.NotFoundException;

public class TestSuiteNotFoundException extends NotFoundException {
    public TestSuiteNotFoundException(String message) {
        super("TEST_SUITE_NOT_FOUND", message);
    }
}
