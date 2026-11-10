package tv.puregrid.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchActivityTest {

    private ContentSearch.Provider provider(String authority) {
        return new ContentSearch.Provider(authority, "io.example.app", "Example");
    }

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

    // ---- sameAuthorities ---------------------------------------------------------------------------

    @Test
    public void sameAuthoritiesIgnoresOrder() {
        List<ContentSearch.Provider> a = Arrays.asList(provider("auth.x"), provider("auth.y"));
        List<ContentSearch.Provider> b = Arrays.asList(provider("auth.y"), provider("auth.x"));
        assertTrue(SearchActivity.sameAuthorities(a, b));
    }

    @Test
    public void sameAuthoritiesComparesSizesFirst() {
        List<ContentSearch.Provider> a = Collections.singletonList(provider("auth.x"));
        List<ContentSearch.Provider> b = Arrays.asList(provider("auth.x"), provider("auth.y"));
        assertFalse(SearchActivity.sameAuthorities(a, b));
    }

    @Test
    public void sameAuthoritiesRejectsDuplicates() {
        // A duplicated authority on either side must not pass: the row lists would disagree.
        List<ContentSearch.Provider> a = Arrays.asList(provider("auth.x"), provider("auth.x"));
        List<ContentSearch.Provider> b = Arrays.asList(provider("auth.x"), provider("auth.y"));
        assertFalse(SearchActivity.sameAuthorities(a, b));
        assertFalse(SearchActivity.sameAuthorities(b, a));
    }

    @Test
    public void sameAuthoritiesEmptyListsMatch() {
        assertTrue(SearchActivity.sameAuthorities(
                Collections.<ContentSearch.Provider>emptyList(),
                Collections.<ContentSearch.Provider>emptyList()));
    }
}