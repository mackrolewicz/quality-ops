package com.qualityops.worker.execution.adapter.out.runner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.worker.execution.application.port.out.ReportParser;
import com.qualityops.worker.execution.exception.ReportParseException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ADR-009 §7 — reads a {@code k6 run --summary-export=summary.json} file: one
 * synthetic {@link RepositoryTestItem} per {@code check} (recursively, k6
 * groups nest) and per breached-or-not {@code threshold}. k6 has no per-test
 * concept, so this breakdown is <strong>best-effort / lower fidelity</strong> —
 * the exact, authoritative PASS/FAIL for the whole repository run comes from
 * the k6 process's exit code, read by {@code RepositoryExecutionRunner}, not
 * from this parser.
 */
@Component
public class K6SummaryReportParser implements ReportParser {

    private final ObjectMapper mapper;

    public K6SummaryReportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RepoReportFormat format() {
        return RepoReportFormat.K6_SUMMARY_JSON;
    }

    @Override
    public List<RepositoryTestItem> parse(List<Path> files) throws ReportParseException {
        if (files == null || files.isEmpty()) {
            throw new ReportParseException("no k6 summary.json resolved from reportPaths");
        }
        Path file = files.get(0);
        JsonNode root;
        try {
            root = mapper.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new ReportParseException("malformed k6 summary JSON: " + file.getFileName(), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new ReportParseException("empty k6 summary JSON: " + file.getFileName());
        }

        var items = new ArrayList<RepositoryTestItem>();
        collectChecks(root.path("root_group"), items);
        collectThresholds(root.path("metrics"), items);
        return items;
    }

    private static void collectChecks(JsonNode group, List<RepositoryTestItem> out) {
        if (group == null || group.isMissingNode()) {
            return;
        }
        JsonNode checks = group.path("checks");
        Iterator<Map.Entry<String, JsonNode>> it = checks.fields();
        while (it.hasNext()) {
            var entry = it.next();
            JsonNode check = entry.getValue();
            long fails = check.path("fails").asLong(0);
            long passes = check.path("passes").asLong(0);
            RepoItemStatus status = fails > 0 ? RepoItemStatus.FAILED : RepoItemStatus.PASSED;
            String message = fails > 0 ? fails + " of " + (fails + passes) + " check invocations failed" : null;
            out.add(new RepositoryTestItem("check", entry.getKey(), status, 0L, null, message));
        }
        Iterator<Map.Entry<String, JsonNode>> groups = group.path("groups").fields();
        while (groups.hasNext()) {
            collectChecks(groups.next().getValue(), out);
        }
    }

    private static void collectThresholds(JsonNode metrics, List<RepositoryTestItem> out) {
        if (metrics == null || metrics.isMissingNode()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> metricIt = metrics.fields();
        while (metricIt.hasNext()) {
            var metricEntry = metricIt.next();
            JsonNode thresholds = metricEntry.getValue().path("thresholds");
            Iterator<Map.Entry<String, JsonNode>> thresholdIt = thresholds.fields();
            while (thresholdIt.hasNext()) {
                var thresholdEntry = thresholdIt.next();
                boolean ok = thresholdEntry.getValue().path("ok").asBoolean(true);
                String name = metricEntry.getKey() + ": " + thresholdEntry.getKey();
                out.add(new RepositoryTestItem("threshold", name,
                    ok ? RepoItemStatus.PASSED : RepoItemStatus.FAILED, 0L, null,
                    ok ? null : "threshold breached: " + name));
            }
        }
    }
}
