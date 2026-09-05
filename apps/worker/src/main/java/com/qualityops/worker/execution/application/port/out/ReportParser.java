package com.qualityops.worker.execution.application.port.out;

import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.worker.execution.exception.ReportParseException;

import java.nio.file.Path;
import java.util.List;

/** ADR-009 §7 — parses a framework's report files into normalized per-test
 *  items. One implementation per {@link RepoReportFormat}, selected by
 *  {@code ReportParserRegistry}. */
public interface ReportParser {

    RepoReportFormat format();

    /** @throws ReportParseException on malformed input or an empty file list —
     *          the caller maps this to case {@code ERROR}, never aborts the run. */
    List<RepositoryTestItem> parse(List<Path> files) throws ReportParseException;
}
