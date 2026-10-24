package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple utility to preserve the order of dock items.
 * In a full implementation this would apply user‑defined ordering, but for now it returns the list unchanged.
 */
public class DockOrder {
    /**
     * Current dock order supplied by the configuration. It can be set by the
     * application at startup (e.g., from {@link Config}). If null, the
     * original input order is preserved.
     */
    private static java.util.List<String> dockApps = null;

    /**
     * Set the dock order that will be used by {@link #reorder(List)}.
     */
    public static void setDockApps(java.util.List<String> apps) {
        dockApps = apps != null ? new java.util.ArrayList<>(apps) : null;
    }
    /**
     * Returns a new list with the same ordering as the input.
     */
    public static List<String> reorder(List<String> input) {
        // Preserve the order defined in the config's dockApps (favourites).
        // Items present in dockApps are placed first, in the order they appear in dockApps.
        // Remaining items keep their original relative order.
        if (input == null) {
            return new ArrayList<>();
        }
        List<String> currentDock = DockOrder.dockApps;
        if (currentDock == null) {
            // No dock order information – return a copy preserving original order.
            return new ArrayList<>(input);
        }
        List<String> result = new ArrayList<>();
        java.util.Set<String> added = new java.util.HashSet<>();
        for (String pkg : currentDock) {
            if (input.contains(pkg) && added.add(pkg)) {
                result.add(pkg);
            }
        }
        for (String pkg : input) {
            if (added.add(pkg)) {
                result.add(pkg);
            }
        }
        return result;
    }
}
