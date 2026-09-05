package com.qualityops.events;

/** One normalized test-case result parsed from a repository run's framework
 *  report. {@code failureMessage} / {@code failureType} are pre-redacted and
 *  truncated by the Worker. */
public record RepositoryTestItem(
        String suite,              // framework classname / describe path; nullable
        String name,
        RepoItemStatus status,
        long durationMillis,
        String failureType,        // nullable
        String failureMessage      // nullable, pre-redacted + truncated by the Worker
) {
    public enum RepoItemStatus { PASSED, FAILED, SKIPPED, ERROR }
}
