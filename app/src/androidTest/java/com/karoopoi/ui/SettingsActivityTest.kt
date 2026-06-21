package com.karoopoi.ui

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.slider.Slider
import com.karoopoi.R
import org.hamcrest.Matcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    @Test
    fun toggleBeachesSwimmingPersists() {
        // Determine initial state
        val initialChecked = try {
            onView(withId(R.id.beaches_swimming_switch)).check(matches(isChecked()))
            true
        } catch (_: AssertionError) {
            false
        }

        onView(withId(R.id.beaches_swimming_switch)).perform(click())
        activityRule.scenario.recreate()

        if (initialChecked) {
            onView(withId(R.id.beaches_swimming_switch)).check(matches(isNotChecked()))
        } else {
            onView(withId(R.id.beaches_swimming_switch)).check(matches(isChecked()))
        }
    }

    @Test
    fun sliderChangesThresholdText() {
        onView(withId(R.id.threshold_slider)).perform(setSliderValue(1500f))
        onView(withId(R.id.threshold_value)).check(matches(withText("1500m")))
    }

    @Test
    fun sliderBoundaryValueChangesThresholdText() {
        onView(withId(R.id.threshold_slider)).perform(setSliderValue(5000f))
        onView(withId(R.id.threshold_value)).check(matches(withText("5000m")))
    }

    private fun setSliderValue(value: Float): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(Slider::class.java)
        override fun getDescription(): String = "Set Slider value to $value"
        override fun perform(uiController: UiController, view: View) {
            (view as Slider).value = value
        }
    }
}
