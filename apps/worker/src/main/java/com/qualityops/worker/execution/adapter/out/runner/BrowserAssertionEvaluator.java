package com.qualityops.worker.execution.adapter.out.runner;

import com.microsoft.playwright.Page;
import com.qualityops.events.BrowserAssertion;
import com.qualityops.worker.execution.domain.BrowserAssertionOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Evaluates declarative browser assertions against the live page. Never throws. */
final class BrowserAssertionEvaluator {

    private static final int SNIPPET_MAX = 120;

    private final SelectorMapper selectors;
    private final Redactor redactor;

    BrowserAssertionEvaluator(SelectorMapper selectors, Redactor redactor) {
        this.selectors = selectors;
        this.redactor = redactor;
    }

    List<BrowserAssertionOutcome> evaluate(Page page, List<BrowserAssertion> assertions,
                                           boolean persistTextSnippets) {
        var out = new ArrayList<BrowserAssertionOutcome>(assertions.size());
        for (BrowserAssertion a : assertions) {
            out.add(evaluateOne(page, a, persistTextSnippets));
        }
        return out;
    }

    private BrowserAssertionOutcome evaluateOne(Page page, BrowserAssertion a, boolean persist) {
        String desc = selectors.describe(a.target());
        try {
            return switch (a.type()) {
                case TEXT_EQUALS, TEXT_CONTAINS -> {
                    String text = safeTrim(selectors.toLocator(page, a.target()).textContent());
                    boolean passed = a.type() == BrowserAssertion.Type.TEXT_EQUALS
                        ? text.equals(nullToEmpty(a.expected()))
                        : text.contains(nullToEmpty(a.expected()));
                    yield outcome(a, desc, passed, actual(text, persist));
                }
                case URL_EQUALS -> {
                    String url = page.url();
                    yield outcome(a, desc, url.equals(nullToEmpty(a.expected())), redactor.url(url));
                }
                case URL_CONTAINS -> {
                    String url = page.url();
                    yield outcome(a, desc, url.contains(nullToEmpty(a.expected())), redactor.url(url));
                }
                case VISIBLE -> {
                    boolean visible = selectors.toLocator(page, a.target()).isVisible();
                    yield outcome(a, desc, visible, String.valueOf(visible));
                }
                case ELEMENT_STATE -> {
                    var loc = selectors.toLocator(page, a.target());
                    String want = nullToEmpty(a.expected()).trim().toLowerCase(Locale.ROOT);
                    boolean passed = switch (want) {
                        case "enabled" -> loc.isEnabled();
                        case "disabled" -> !loc.isEnabled();
                        case "checked" -> loc.isChecked();
                        case "unchecked" -> !loc.isChecked();
                        case "editable" -> loc.isEditable();
                        case "hidden" -> loc.isHidden();
                        default -> false;
                    };
                    yield outcome(a, desc, passed, want);
                }
            };
        } catch (RuntimeException e) {   // PlaywrightException, IllegalArgumentException, timeout…
            return outcome(a, desc, false, "(error: " + e.getClass().getSimpleName() + ")");
        }
    }

    private BrowserAssertionOutcome outcome(BrowserAssertion a, String desc, boolean passed, String actual) {
        return new BrowserAssertionOutcome(a.type(), desc, redactor.value(a.expected()), actual, passed);
    }

    private String actual(String text, boolean persist) {
        if (!persist) {
            return "(text suppressed)";
        }
        String snippet = text.length() <= SNIPPET_MAX ? text : text.substring(0, SNIPPET_MAX);
        return redactor.value(snippet);
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
