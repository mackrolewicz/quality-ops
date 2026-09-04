package com.qualityops.worker.execution.adapter.out.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.Selector;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.execution.domain.BrowserAssertionOutcome;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(10)
class BrowserAssertionEvaluatorTest {

    private final Redactor redactor = new Redactor(TestProps.defaults(Mode.AUTO, java.time.Duration.ofMinutes(5),
        new Ssrf(false, java.util.List.of(), java.util.List.of()),
        new Redaction(java.util.List.of(), java.util.List.of()), false));
    private final BrowserAssertionEvaluator evaluator =
        new BrowserAssertionEvaluator(new SelectorMapper(), redactor);

    @Mock
    private Page page;

    @Mock
    private Locator locator;

    private static final Selector MSG = new Selector(Selector.Strategy.TEST_ID, "msg", null, null);

    @Test
    void textEquals_matchingText_passes() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.textContent()).thenReturn("Saved");

        var out = one(new BrowserAssertion(BrowserAssertion.Type.TEXT_EQUALS, MSG, "Saved"), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void textContains_substring_passes() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.textContent()).thenReturn("all saved now");

        var out = one(new BrowserAssertion(BrowserAssertion.Type.TEXT_CONTAINS, MSG, "saved"), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void urlEquals_exactMatch_passes() {
        when(page.url()).thenReturn("https://app.test/home");

        var out = one(new BrowserAssertion(BrowserAssertion.Type.URL_EQUALS, null, "https://app.test/home"), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void urlContains_substring_passes() {
        when(page.url()).thenReturn("https://app.test/home?x=1");

        var out = one(new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/home"), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void visible_locatorVisible_passes() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.isVisible()).thenReturn(true);

        var out = one(new BrowserAssertion(BrowserAssertion.Type.VISIBLE, MSG, null), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void elementState_enabled_passes() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.isEnabled()).thenReturn(true);

        var out = one(new BrowserAssertion(BrowserAssertion.Type.ELEMENT_STATE, MSG, "enabled"), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void elementState_unchecked_mapsToNotChecked() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.isChecked()).thenReturn(false);

        var out = one(new BrowserAssertion(BrowserAssertion.Type.ELEMENT_STATE, MSG, "unchecked"), true);

        assertThat(out.passed()).isTrue();
    }

    @Test
    void elementState_bogusExpected_failsWithoutThrowing() {
        when(page.getByTestId("msg")).thenReturn(locator);

        var out = one(new BrowserAssertion(BrowserAssertion.Type.ELEMENT_STATE, MSG, "sparkling"), true);

        assertThat(out.passed()).isFalse();
    }

    @Test
    void textEquals_persistTextSnippetsFalse_actualSuppressed() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.textContent()).thenReturn("Saved");

        var out = one(new BrowserAssertion(BrowserAssertion.Type.TEXT_EQUALS, MSG, "Nope"), false);

        assertThat(out.actual()).isEqualTo("(text suppressed)");
    }

    @Test
    void textEquals_persistTextSnippetsTrue_actualIsRedactedSnippet() {
        when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.textContent()).thenReturn("Saved");

        var out = one(new BrowserAssertion(BrowserAssertion.Type.TEXT_EQUALS, MSG, "Nope"), true);

        assertThat(out.actual()).isEqualTo("Saved");
    }

    @Test
    void evaluate_locatorThrows_returnsFailedOutcome_neverThrows() {
        lenient().when(page.getByTestId("msg")).thenReturn(locator);
        when(locator.textContent()).thenThrow(new PlaywrightException("boom"));

        var out = one(new BrowserAssertion(BrowserAssertion.Type.TEXT_EQUALS, MSG, "Saved"), true);

        assertThat(out.passed()).isFalse();
        assertThat(out.actual()).startsWith("(error:");
    }

    private BrowserAssertionOutcome one(BrowserAssertion a, boolean persist) {
        List<BrowserAssertionOutcome> out = evaluator.evaluate(page, List.of(a), persist);
        return out.get(0);
    }
}
