package com.qualityops.events;

/** How a {@link BrowserStep}/{@link BrowserAssertion} locates a DOM element.
 *  ROLE uses roleName (+ optional accessibleName); every other strategy uses value. */
public record Selector(Strategy strategy, String value, String roleName, String accessibleName) {
    public enum Strategy { ROLE, LABEL, TEST_ID, TEXT, CSS }
}
