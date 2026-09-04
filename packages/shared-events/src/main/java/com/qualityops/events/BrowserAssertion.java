package com.qualityops.events;

/** One assertion evaluated against the page after the steps run.
 *  ELEMENT_STATE: expected in {enabled,disabled,checked,unchecked,editable,hidden}. */
public record BrowserAssertion(Type type, Selector target, String expected) {
    public enum Type { TEXT_EQUALS, TEXT_CONTAINS, URL_EQUALS, URL_CONTAINS, VISIBLE, ELEMENT_STATE }
}
