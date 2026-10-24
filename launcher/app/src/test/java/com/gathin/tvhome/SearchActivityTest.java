package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchActivityTest {
    @Test
    public void emptyQueryMatchesAll() {
        assertTrue(SearchActivity.matches("YouTube", "com.google.youtube", ""));
        assertTrue(SearchActivity.matches("YouTube", "com.google.youtube", null));
    }

    @Test
    public void matchesLabelCaseInsensitive() {
        assertTrue(SearchActivity.matches("YouTube", "com.google.youtube", "tube"));
        assertTrue(SearchActivity.matches("YouTube", "com.google.youtube", "YOUTUBE"));
    }

    @Test
    public void matchesPackage() {
        assertTrue(SearchActivity.matches("YouTube", "com.google.youtube", "google"));
    }

    @Test
    public void noMatch() {
        assertFalse(SearchActivity.matches("YouTube", "com.google.youtube", "netflix"));
    }

    @Test
    public void nullSafe() {
        assertFalse(SearchActivity.matches(null, null, "x"));
        assertTrue(SearchActivity.matches(null, null, ""));
    }
}