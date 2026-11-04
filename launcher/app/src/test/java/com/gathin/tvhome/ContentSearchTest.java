package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The launch-intent assembly and dedup of ContentSearch — the part that decides whether a
 * suggestion row opens the right page inside the source app. Pure strings, no Android runtime.
 */
public class ContentSearchTest {

    private static ContentSearch.Result row(String title, String action, String data, String dataId,
                                            String extraData, String query) {
        return new ContentSearch.Result(title, null, null, "com.example.tv",
                action, data, dataId, extraData, query);
    }

    private static final String AUTHORITY = "com.example.tv.search";

    @Test
    public void dataDefaultsToView() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, "content://com.example.tv/film/42", null, null, null), AUTHORITY);
        assertEquals("android.intent.action.VIEW", s.action);
        assertEquals("content://com.example.tv/film/42", s.data);
        assertNull(s.query);
        assertNull(s.extraData);
    }

    @Test
    public void dataIdAppendsToData() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, "content://com.example.tv/film", "42", null, null), AUTHORITY);
        assertEquals("android.intent.action.VIEW", s.action);
        assertEquals("content://com.example.tv/film/42", s.data);
    }

    @Test
    public void dataIdAppendsToTrailingSlashData() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, "content://com.example.tv/film/", "42", null, null), AUTHORITY);
        assertEquals("content://com.example.tv/film/42", s.data);
    }

    @Test
    public void dataIdAloneUsesProviderAuthority() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, null, "42", null, null), AUTHORITY);
        assertEquals("android.intent.action.VIEW", s.action);
        assertEquals("content://com.example.tv.search/42", s.data);
    }

    @Test
    public void dataIdKeepsQueryAQuery() {
        // The id belongs to the path, not to a query value: "content://x/film?partner=1" + "42"
        // must become "content://x/film/42?partner=1", not ".../film?partner=1/42".
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, "content://com.example.tv/film?partner=1", "42", null, null), AUTHORITY);
        assertEquals("android.intent.action.VIEW", s.action);
        assertEquals("content://com.example.tv/film/42?partner=1", s.data);
    }

    @Test
    public void dataIdKeepsQueryAQueryWithTrailingSlash() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, "content://com.example.tv/film/?a=b", "42", null, null), AUTHORITY);
        assertEquals("content://com.example.tv/film/42?a=b", s.data);
    }

    @Test
    public void queryAloneBecomesSearch() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, null, null, null, "the matrix"), AUTHORITY);
        assertEquals("android.intent.action.SEARCH", s.action);
        assertNull(s.data);
        assertEquals("the matrix", s.query);
    }

    @Test
    public void explicitActionWins() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", "com.example.tv.OPEN_DETAIL", "content://com.example.tv/film/42",
                        null, null, null), AUTHORITY);
        assertEquals("com.example.tv.OPEN_DETAIL", s.action);
        assertEquals("content://com.example.tv/film/42", s.data);
    }

    @Test
    public void extraDataIsKept() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", null, "content://com.example.tv/film/42", null,
                        "{\"id\":42}", null), AUTHORITY);
        assertEquals("{\"id\":42}", s.extraData);
    }

    @Test
    public void emptyColumnsMeanAbsent() {
        ContentSearch.LaunchSpec s = ContentSearch.buildLaunch(
                row("Film", "", "content://com.example.tv/film/42", "", "", ""), AUTHORITY);
        assertEquals("android.intent.action.VIEW", s.action);
        assertNull(s.query);
        assertNull(s.extraData);
    }

    @Test
    public void nothingToOpenIsNull() {
        assertNull(ContentSearch.buildLaunch(row("Film", null, null, null, null, null), AUTHORITY));
        assertNull(ContentSearch.buildLaunch(null, AUTHORITY));
    }

    @Test
    public void dedupKeepsFirstPerAppAndTitle() {
        ContentSearch.Result a = row("The Matrix", null, "content://x/1", null, null, null);
        ContentSearch.Result b = row("The Matrix", null, "content://x/2", null, null, null);
        ContentSearch.Result c = row("the matrix", null, "content://x/3", null, null, null);
        ContentSearch.Result d = row("Split", null, "content://x/4", null, null, null);
        List<ContentSearch.Result> out = ContentSearch.dedup(Arrays.asList(a, b, c, d));
        assertEquals(2, out.size());
        assertSame(a, out.get(0));
        assertSame(d, out.get(1));
    }

    @Test
    public void dedupSeparatesApps() {
        ContentSearch.Result a = row("The Matrix", null, "content://x/1", null, null, null);
        ContentSearch.Result other = new ContentSearch.Result("The Matrix", null, null, "com.other.tv",
                null, null, null, null, null);
        List<ContentSearch.Result> out = ContentSearch.dedup(Arrays.asList(a, other));
        assertEquals(2, out.size());
    }

    @Test
    public void dedupSurvivesNulls() {
        List<ContentSearch.Result> in = new ArrayList<>();
        in.add(new ContentSearch.Result(null, null, null, null, null, null, null, null, null));
        in.add(row("Film", null, null, null, null, null));
        assertEquals(2, ContentSearch.dedup(in).size());
        assertTrue(ContentSearch.dedup(new ArrayList<>()).isEmpty());
    }
}
