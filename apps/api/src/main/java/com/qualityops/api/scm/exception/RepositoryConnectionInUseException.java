package com.qualityops.api.scm.exception;

import com.qualityops.api.common.ConflictException;

import java.util.UUID;

/** ADR-009 §11 — a non-deleted {@code test_cases.repo_test} still references this
 *  connection (-> 409 {@code CONNECTION_IN_USE}). */
public class RepositoryConnectionInUseException extends ConflictException {

    public RepositoryConnectionInUseException(UUID id, long referencingCases) {
        super("CONNECTION_IN_USE",
            "Repository connection " + id + " is referenced by " + referencingCases + " test case(s)");
    }
}
