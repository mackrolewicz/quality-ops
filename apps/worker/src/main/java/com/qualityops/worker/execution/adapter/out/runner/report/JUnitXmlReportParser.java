package com.qualityops.worker.execution.adapter.out.runner.report;

import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.worker.execution.application.port.out.ReportParser;
import com.qualityops.worker.execution.exception.ReportParseException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.xml.sax.SAXException;

/**
 * ADR-009 §7 — one parser covers every framework that emits JUnit-style XML:
 * Playwright ({@code --reporter=junit}), JUnit/Surefire, pytest
 * ({@code --junitxml}), and Cypress ({@code mocha-junit-reporter} /
 * {@code cypress-multi-reporters}). Namespace-tolerant (matches by local
 * element name) and handles nested {@code <testsuites>}. A {@code <testcase>}
 * with both {@code <failure>} and {@code <system-out>} keeps only the
 * (caller-)redacted {@code <failure>} content — {@code <system-out>} is never read.
 */
@Component
public class JUnitXmlReportParser implements ReportParser {

    private static final int MAX_ITEMS = 5000;

    @Override
    public RepoReportFormat format() {
        return RepoReportFormat.JUNIT_XML;
    }

    @Override
    public List<RepositoryTestItem> parse(List<Path> files) throws ReportParseException {
        if (files == null || files.isEmpty()) {
            throw new ReportParseException("no JUnit XML report file resolved from reportPaths");
        }
        var items = new ArrayList<RepositoryTestItem>();
        for (Path file : files) {
            parseFile(file, items);
            if (items.size() > MAX_ITEMS) {
                items = new ArrayList<>(items.subList(0, MAX_ITEMS));
                break;
            }
        }
        return items;
    }

    private void parseFile(Path file, List<RepositoryTestItem> out) throws ReportParseException {
        Document doc;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            doc = factory.newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ReportParseException("malformed JUnit XML report: " + file.getFileName(), e);
        }

        for (Element testcase : elementsByLocalName(doc, "testcase")) {
            out.add(toItem(testcase));
        }
    }

    private static RepositoryTestItem toItem(Element testcase) {
        String name = attr(testcase, "name", "unnamed test");
        String suite = attrOrNull(testcase, "classname");
        long durationMillis = parseSeconds(attr(testcase, "time", "0"));

        List<Element> failures = childrenByLocalName(testcase, "failure");
        List<Element> errors = childrenByLocalName(testcase, "error");
        List<Element> skipped = childrenByLocalName(testcase, "skipped");

        RepoItemStatus status;
        String failureType = null;
        String failureMessage = null;
        if (!skipped.isEmpty()) {
            status = RepoItemStatus.SKIPPED;
        } else if (!errors.isEmpty()) {
            status = RepoItemStatus.ERROR;
            failureType = attrOrNull(errors.get(0), "type");
            failureMessage = messageOf(errors.get(0));
        } else if (!failures.isEmpty()) {
            status = RepoItemStatus.FAILED;
            failureType = attrOrNull(failures.get(0), "type");
            failureMessage = messageOf(failures.get(0));
        } else {
            status = RepoItemStatus.PASSED;
        }
        return new RepositoryTestItem(suite, name, status, durationMillis, failureType, failureMessage);
    }

    private static String messageOf(Element failureOrError) {
        String message = attrOrNull(failureOrError, "message");
        if (message != null && !message.isBlank()) {
            return message;
        }
        String text = failureOrError.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static long parseSeconds(String time) {
        try {
            return Math.round(Double.parseDouble(time) * 1000);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String attr(Element e, String name, String fallback) {
        String v = attrOrNull(e, name);
        return v == null ? fallback : v;
    }

    private static String attrOrNull(Element e, String name) {
        if (!e.hasAttribute(name)) {
            return null;
        }
        String v = e.getAttribute(name);
        return v.isBlank() ? null : v;
    }

    /** Namespace-tolerant: matches by local (unprefixed) element name so a
     *  namespaced report (e.g. {@code <ns:testcase>}) is still found. */
    private static List<Element> elementsByLocalName(Document doc, String localName) {
        return filterByLocalName(doc.getElementsByTagName("*"), localName);
    }

    private static List<Element> childrenByLocalName(Element parent, String localName) {
        return filterByLocalName(parent.getChildNodes(), localName);
    }

    private static List<Element> filterByLocalName(NodeList nodes, String localName) {
        var out = new ArrayList<Element>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element el && localNameOf(el).equals(localName)) {
                out.add(el);
            }
        }
        return out;
    }

    private static String localNameOf(Element el) {
        String tag = el.getTagName();
        int colon = tag.indexOf(':');
        return (colon < 0 ? tag : tag.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }
}
