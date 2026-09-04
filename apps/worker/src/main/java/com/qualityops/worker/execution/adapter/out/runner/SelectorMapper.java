package com.qualityops.worker.execution.adapter.out.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.qualityops.events.Selector;

import java.util.Locale;

/** Maps a wire {@link Selector} to a Playwright {@link Locator} and to a
 *  log-safe description. Never leaks a FILL value (selectors carry none). */
final class SelectorMapper {

    Locator toLocator(Page page, Selector s) {
        if (s == null) {
            throw new IllegalArgumentException("null selector");
        }
        return switch (s.strategy()) {
            case ROLE -> {
                AriaRole role = AriaRole.valueOf(requireText(s.roleName(), "roleName")
                    .trim().toUpperCase(Locale.ROOT));
                var opts = new Page.GetByRoleOptions();
                if (s.accessibleName() != null && !s.accessibleName().isBlank()) {
                    opts.setName(s.accessibleName());
                }
                yield page.getByRole(role, opts);
            }
            case LABEL -> page.getByLabel(requireText(s.value(), "value"));
            case TEST_ID -> page.getByTestId(requireText(s.value(), "value"));
            case TEXT -> page.getByText(requireText(s.value(), "value"));
            case CSS -> page.locator(requireText(s.value(), "value"));
        };
    }

    String describe(Selector s) {
        if (s == null) {
            return "(no selector)";
        }
        return switch (s.strategy()) {
            case ROLE -> "role=" + s.roleName()
                + (s.accessibleName() == null || s.accessibleName().isBlank()
                    ? "" : "[name=" + s.accessibleName() + "]");
            case LABEL -> "label=" + s.value();
            case TEST_ID -> "testId=" + s.value();
            case TEXT -> "text=" + s.value();
            case CSS -> "css=" + s.value();
        };
    }

    private static String requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("selector " + field + " is required");
        }
        return v;
    }
}
