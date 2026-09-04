package com.qualityops.api.scm.exception;

import com.qualityops.api.common.NotFoundException;

import java.util.UUID;

/** ADR-009 §4 — connection absent or owned by another org/project (-> 404
 *  {@code REPOSITORY_CONNECTION_NOT_FOUND}). */
public class RepositoryConnectionNotFoundException extends NotFoundException {

    public RepositoryConnectionNotFoundException(UUID id) {
        super("REPOSITORY_CONNECTION_NOT_FOUND", "Repository connection not found: " + id);
    }
}
