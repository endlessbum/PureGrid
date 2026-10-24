//package com.gathin.tvhome;

import android.content.Intent;
import android.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/**
 * UI test for {@link ModuleCardsActivity} – verifies that the activity starts and the RecyclerView
 * for the module cards is displayed. The test does not depend on real card data; it only checks the
 * view hierarchy. More comprehensive tests (e.g., checking item count, scrolling) can be added later.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ModuleCardsActivityTest {

    @Test
    public void activityLaunchesAndShowsRecyclerView() {
        // Launch the activity.
        try (ActivityScenario<ModuleCardsActivity> scenario = ActivityScenario.launch(ModuleCardsActivity.class)) {
            // Verify that the RecyclerView with id "recycler" is displayed.
            onView(withId(R.id.recycler)).check(matches(isDisplayed()));
        }
    }
}
