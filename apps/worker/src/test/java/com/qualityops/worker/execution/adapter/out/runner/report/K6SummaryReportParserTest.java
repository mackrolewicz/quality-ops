package com.qualityops.worker.execution.adapter.out.runner.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.worker.execution.exception.ReportParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 §7 — k6 {@code --summary-export} parsing: one item per check
 *  (recursively through groups) and per threshold; best-effort / lower
 *  fidelity by design (the exact PASS/FAIL comes from the exit code, read
 *  elsewhere by {@code RepositoryExecutionRunner}). */
class K6SummaryReportParserTest {

    private final K6SummaryReportParser parser = new K6SummaryReportParser(new ObjectMapper());

    @Test
    void format_isK6SummaryJson() {
        assertThat(parser.format()).isEqualTo(RepoReportFormat.K6_SUMMARY_JSON);
    }

    @Test
    void parse_checksAndThresholds_mapsPassAndFail(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
            {
              "metrics": {
                "http_req_duration": {
                  "thresholds": { "p(95)<500": { "ok": true } }
                },
                "http_req_failed": {
                  "thresholds": { "rate<0.01": { "ok": false } }
                }
              },
              "root_group": {
                "checks": {
                  "status is 200": { "passes": 10, "fails": 0 },
                  "body contains ok": { "passes": 8, "fails": 2 }
                },
                "groups": {
                  "sub group": {
                    "checks": { "nested check": { "passes": 1, "fails": 0 } },
                    "groups": {}
                  }
                }
              }
            }
            """);

        var items = parser.parse(List.of(file));

        assertThat(items).hasSize(5);
        assertThat(items).filteredOn(i -> i.name().equals("status is 200")).singleElement()
            .satisfies(i -> assertThat(i.status()).isEqualTo(RepoItemStatus.PASSED));
        assertThat(items).filteredOn(i -> i.name().equals("body contains ok")).singleElement()
            .satisfies(i -> assertThat(i.status()).isEqualTo(RepoItemStatus.FAILED));
        assertThat(items).filteredOn(i -> i.name().equals("nested check")).singleElement()
            .satisfies(i -> assertThat(i.status()).isEqualTo(RepoItemStatus.PASSED));
        assertThat(items).filteredOn(i -> i.name().contains("p(95)<500")).singleElement()
            .satisfies(i -> assertThat(i.status()).isEqualTo(RepoItemStatus.PASSED));
        assertThat(items).filteredOn(i -> i.name().contains("rate<0.01")).singleElement()
            .satisfies(i -> assertThat(i.status()).isEqualTo(RepoItemStatus.FAILED));
    }

    @Test
    void parse_missingFile_throwsReportParseException() {
        assertThatThrownBy(() -> parser.parse(List.of())).isInstanceOf(ReportParseException.class);
    }

    @Test
    void parse_malformedJson_throwsReportParseException(@TempDir Path dir) throws Exception {
        Path file = write(dir, "{not-json");

        assertThatThrownBy(() -> parser.parse(List.of(file))).isInstanceOf(ReportParseException.class);
    }

    private static Path write(Path dir, String content) throws Exception {
        Path file = dir.resolve("summary.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
