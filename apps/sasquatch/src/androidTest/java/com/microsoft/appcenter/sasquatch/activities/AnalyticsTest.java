/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.sasquatch.activities;


import android.support.test.espresso.IdlingRegistry;
import android.support.test.rule.ActivityTestRule;

import com.microsoft.appcenter.sasquatch.R;
import com.microsoft.appcenter.sasquatch.listeners.SasquatchAnalyticsListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.closeSoftKeyboard;
import static android.support.test.espresso.action.ViewActions.replaceText;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.isRoot;
import static android.support.test.espresso.matcher.ViewMatchers.withChild;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static com.microsoft.appcenter.sasquatch.activities.utils.EspressoUtils.CHECK_DELAY;
import static com.microsoft.appcenter.sasquatch.activities.utils.EspressoUtils.TOAST_DELAY;
import static com.microsoft.appcenter.sasquatch.activities.utils.EspressoUtils.onToast;
import static com.microsoft.appcenter.sasquatch.activities.utils.EspressoUtils.waitFor;
import static com.microsoft.appcenter.sasquatch.activities.utils.EspressoUtils.withContainsText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;

@SuppressWarnings("unused")
public class AnalyticsTest {

    /**
     * The same setting as SDK that is package private. Module default batching interval.
     */
    private static final int DEFAULT_TRIGGER_INTERVAL = 3000;

    @Rule
    public final ActivityTestRule<MainActivity> mActivityTestRule = new ActivityTestRule<>(MainActivity.class);

    @Before
    public void setUp() {
        IdlingRegistry.getInstance().register(SasquatchAnalyticsListener.analyticsIdlingResource);
    }

    @After
    public void tearDown() {
        IdlingRegistry.getInstance().unregister(SasquatchAnalyticsListener.analyticsIdlingResource);
    }

    @Test
    public void sendEventTest() throws InterruptedException {

        /* Send event. */
        onView(allOf(
                withChild(withText(R.string.title_event)),
                withChild(withText(R.string.description_event))))
                .perform(click());
        onView(withId(R.id.name)).perform(replaceText("test"), closeSoftKeyboard());
        onView(withText(R.string.send)).perform(click());

        /* Check toasts. */
        waitFor(onToast(mActivityTestRule.getActivity(),
                withText(R.string.event_before_sending)), DEFAULT_TRIGGER_INTERVAL + CHECK_DELAY)
                .check(matches(isDisplayed()));
        onView(isRoot()).perform(waitFor(TOAST_DELAY));
        waitFor(onToast(mActivityTestRule.getActivity(), anyOf(
                withContainsText(R.string.event_sent_succeeded),
                withContainsText(R.string.event_sent_failed))), TOAST_DELAY)
                .check(matches(isDisplayed()));
    }

    @Test
    public void sendPageTest() throws InterruptedException {

        /* Send page. */
        onView(allOf(
                withChild(withText(R.string.title_page)),
                withChild(withText(R.string.description_page))))
                .perform(click());
        onView(withId(R.id.name)).perform(replaceText("test"), closeSoftKeyboard());
        onView(withText(R.string.send)).perform(click());

        /* Check toasts. */
        waitFor(onToast(mActivityTestRule.getActivity(), withText(R.string.page_before_sending)), DEFAULT_TRIGGER_INTERVAL + CHECK_DELAY)
                .check(matches(isDisplayed()));
        onView(isRoot()).perform(waitFor(TOAST_DELAY));
        waitFor(onToast(mActivityTestRule.getActivity(), anyOf(
                withContainsText(R.string.page_sent_succeeded),
                withContainsText(R.string.page_sent_failed))), TOAST_DELAY)
                .check(matches(isDisplayed()));
    }
}
