package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class DockOrderTest {
    @Test public void nullInputReturnsEmpty() {
        DockOrder.setDockApps(null);
        assertEquals(0, DockOrder.reorder(null).size());
    }

    @Test public void nullDockPreservesInputOrder() {
        DockOrder.setDockApps(null);
        List<String> input = Arrays.asList("a", "b", "c");
        assertEquals(input, DockOrder.reorder(input));
    }

    @Test public void dockOrderPlacesDockItemsFirst() {
        DockOrder.setDockApps(Arrays.asList("b", "a"));
        List<String> input = Arrays.asList("c", "a", "b", "d");
        assertEquals(Arrays.asList("b", "a", "c", "d"), DockOrder.reorder(input));
    }

    @Test public void missingDockItemsAreIgnored() {
        DockOrder.setDockApps(Arrays.asList("x", "y"));
        List<String> input = Arrays.asList("a", "b");
        assertEquals(Arrays.asList("a", "b"), DockOrder.reorder(input));
    }

    @Test public void duplicatesInInputAreDeduped() {
        DockOrder.setDockApps(Arrays.asList("a"));
        List<String> input = Arrays.asList("a", "a", "b");
        assertEquals(Arrays.asList("a", "b"), DockOrder.reorder(input));
    }
}