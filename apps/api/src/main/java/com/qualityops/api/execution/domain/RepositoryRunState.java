package com.qualityops.api.execution.domain;

/** ADR-009 §3/§13 — {@code repository_run.state}, derived by the API: PENDING at
 *  enqueue, RUNNING on {@code runs.started}, COMPLETED/FAILED on the terminal,
 *  CANCELLED on a QUEUED-phase cancel. {@code VARCHAR + CHECK}, never a PG enum. */
public enum RepositoryRunState { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
