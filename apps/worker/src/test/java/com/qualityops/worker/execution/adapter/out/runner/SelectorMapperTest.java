package com.qualityops.worker.execution.adapter.out.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.qualityops.events.Selector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SelectorMapperTest {

    private final SelectorMapper mapper = new SelectorMapper();

    @Mock
    private Page page;

    @Mock
    private Locator locator;

    @Test
    void toLocator_role_callsGetByRoleWithName() {
        when(page.getByRole(eq(AriaRole.BUTTON), any())).thenReturn(locator);

        var result = mapper.toLocator(page, new Selector(Selector.Strategy.ROLE, null, "button", "Go"));

        assertThat(result).isSameAs(locator);
        verify(page).getByRole(eq(AriaRole.BUTTON), any());
    }

    @Test
    void toLocator_roleWithoutAccessibleName_omitsSetName() {
        when(page.getByRole(eq(AriaRole.BUTTON), any())).thenReturn(locator);

        var result = mapper.toLocator(page, new Selector(Selector.Strategy.ROLE, null, "button", null));

        assertThat(result).isSameAs(locator);
    }

    @Test
    void toLocator_label_callsGetByLabel() {
        when(page.getByLabel("Email")).thenReturn(locator);

        assertThat(mapper.toLocator(page, new Selector(Selector.Strategy.LABEL, "Email", null, null)))
            .isSameAs(locator);
    }

    @Test
    void toLocator_testId_callsGetByTestId() {
        when(page.getByTestId("msg")).thenReturn(locator);

        assertThat(mapper.toLocator(page, new Selector(Selector.Strategy.TEST_ID, "msg", null, null)))
            .isSameAs(locator);
    }

    @Test
    void toLocator_text_callsGetByText() {
        when(page.getByText("Welcome")).thenReturn(locator);

        assertThat(mapper.toLocator(page, new Selector(Selector.Strategy.TEXT, "Welcome", null, null)))
            .isSameAs(locator);
    }

    @Test
    void toLocator_css_callsLocator() {
        when(page.locator("#go")).thenReturn(locator);

        assertThat(mapper.toLocator(page, new Selector(Selector.Strategy.CSS, "#go", null, null)))
            .isSameAs(locator);
    }

    @Test
    void toLocator_unknownRole_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> mapper.toLocator(page,
            new Selector(Selector.Strategy.ROLE, null, "wizard", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toLocator_roleStrategyMissingRoleName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> mapper.toLocator(page,
            new Selector(Selector.Strategy.ROLE, null, null, "Go")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describe_everyStrategy_containsStrategyTokenNotSecret() {
        assertThat(mapper.describe(new Selector(Selector.Strategy.ROLE, null, "button", "Go"))).startsWith("role=");
        assertThat(mapper.describe(new Selector(Selector.Strategy.LABEL, "Email", null, null))).startsWith("label=");
        assertThat(mapper.describe(new Selector(Selector.Strategy.TEST_ID, "msg", null, null))).startsWith("testId=");
        assertThat(mapper.describe(new Selector(Selector.Strategy.TEXT, "Welcome", null, null))).startsWith("text=");
        assertThat(mapper.describe(new Selector(Selector.Strategy.CSS, "#go", null, null))).startsWith("css=");
        assertThat(mapper.describe(null)).isEqualTo("(no selector)");
    }
}
