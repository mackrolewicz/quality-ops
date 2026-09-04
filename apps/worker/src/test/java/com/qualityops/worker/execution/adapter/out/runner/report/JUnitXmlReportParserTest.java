package com.qualityops.worker.execution.adapter.out.runner.report;

import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.worker.execution.exception.ReportParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 §7 — one parser covers Surefire, pytest, Playwright, and Cypress
 *  JUnit-XML variants. */
class JUnitXmlReportParserTest {

    private final JUnitXmlReportParser parser = new JUnitXmlReportParser();

    @Test
    void format_isJunitXml() {
        assertThat(parser.format()).isEqualTo(RepoReportFormat.JUNIT_XML);
    }

    @Test
    void parse_surefireStyleReport_mapsPassAndFail(@TempDir Path dir) throws Exception {
        Path file = write(dir, "surefire.xml", """
            <testsuite name="com.example.FooTest" tests="2" failures="1" errors="0" skipped="0">
              <testcase classname="com.example.FooTest" name="testOne" time="0.012"/>
              <testcase classname="com.example.FooTest" name="testTwo" time="0.034">
                <failure message="expected true but was false" type="java.lang.AssertionError">
                  at com.example.FooTest.testTwo(FooTest.java:42)
                </failure>
              </testcase>
            </testsuite>
            """);

        List<RepositoryTestItem> items = parser.parse(List.of(file));

        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(i -> {
            assertThat(i.name()).isEqualTo("testOne");
            assertThat(i.status()).isEqualTo(RepoItemStatus.PASSED);
            assertThat(i.durationMillis()).isEqualTo(12);
        });
        assertThat(items).anySatisfy(i -> {
            assertThat(i.name()).isEqualTo("testTwo");
            assertThat(i.status()).isEqualTo(RepoItemStatus.FAILED);
            assertThat(i.failureType()).isEqualTo("java.lang.AssertionError");
            assertThat(i.failureMessage()).isEqualTo("expected true but was false");
        });
    }

    @Test
    void parse_pytestStyleNestedTestsuites_handlesErrorAndSkipped(@TempDir Path dir) throws Exception {
        Path file = write(dir, "pytest.xml", """
            <testsuites>
              <testsuite name="pytest" tests="3" failures="0" errors="1" skipped="1">
                <testcase classname="tests.test_app" name="test_pass" time="0.001"/>
                <testcase classname="tests.test_app" name="test_error" time="0.003">
                  <error message="boom" type="RuntimeError">traceback</error>
                </testcase>
                <testcase classname="tests.test_app" name="test_skip" time="0.000">
                  <skipped message="not applicable"/>
                </testcase>
              </testsuite>
            </testsuites>
            """);

        List<RepositoryTestItem> items = parser.parse(List.of(file));

        assertThat(items).hasSize(3);
        assertThat(items).filteredOn(i -> i.name().equals("test_error")).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(RepoItemStatus.ERROR);
            assertThat(i.failureType()).isEqualTo("RuntimeError");
        });
        assertThat(items).filteredOn(i -> i.name().equals("test_skip")).singleElement()
            .satisfies(i -> assertThat(i.status()).isEqualTo(RepoItemStatus.SKIPPED));
    }

    @Test
    void parse_playwrightJunitReporter_keepsOnlyFailureIgnoresSystemOut(@TempDir Path dir) throws Exception {
        Path file = write(dir, "playwright.xml", """
            <testsuites>
              <testsuite name="login.spec.ts">
                <testcase classname="login.spec.ts &gt; login flow" name="logs in" time="1.2">
                  <failure message="Timed out waiting for selector">the real failure text</failure>
                  <system-out>noisy console output that must be ignored</system-out>
                </testcase>
              </testsuite>
            </testsuites>
            """);

        List<RepositoryTestItem> items = parser.parse(List.of(file));

        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(RepoItemStatus.FAILED);
            assertThat(i.failureMessage()).isEqualTo("Timed out waiting for selector");
            assertThat(i.failureMessage()).doesNotContain("noisy console output");
        });
    }

    @Test
    void parse_cypressMochaJunitReporter_mapsPassed(@TempDir Path dir) throws Exception {
        Path file = write(dir, "cypress.xml", """
            <testsuites>
              <testsuite name="cypress/e2e/login.cy.js" tests="1">
                <testcase classname="Login" name="should log in successfully" time="2.5"/>
              </testsuite>
            </testsuites>
            """);

        List<RepositoryTestItem> items = parser.parse(List.of(file));

        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(RepoItemStatus.PASSED);
            assertThat(i.durationMillis()).isEqualTo(2500);
        });
    }

    @Test
    void parse_malformedXml_throwsReportParseException(@TempDir Path dir) throws Exception {
        Path file = write(dir, "broken.xml", "<testsuite><testcase name=\"x\"");

        assertThatThrownBy(() -> parser.parse(List.of(file))).isInstanceOf(ReportParseException.class);
    }

    @Test
    void parse_noFiles_throwsReportParseException() {
        assertThatThrownBy(() -> parser.parse(List.of())).isInstanceOf(ReportParseException.class);
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
