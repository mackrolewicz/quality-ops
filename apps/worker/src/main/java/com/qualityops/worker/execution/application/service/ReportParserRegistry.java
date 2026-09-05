package com.qualityops.worker.execution.application.service;

import com.qualityops.events.RepoReportFormat;
import com.qualityops.worker.execution.application.port.out.ReportParser;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** ADR-009 §7 — maps a {@link RepoReportFormat} to its {@link ReportParser}. */
@Component
public class ReportParserRegistry {

    private final Map<RepoReportFormat, ReportParser> byFormat = new EnumMap<>(RepoReportFormat.class);

    public ReportParserRegistry(List<ReportParser> parsers) {
        parsers.forEach(p -> byFormat.put(p.format(), p));
    }

    public ReportParser get(RepoReportFormat format) {
        ReportParser parser = byFormat.get(format);
        if (parser == null) {
            throw new IllegalStateException("No ReportParser registered for " + format);
        }
        return parser;
    }
}
