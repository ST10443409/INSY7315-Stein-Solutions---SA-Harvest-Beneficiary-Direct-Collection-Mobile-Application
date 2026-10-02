package com.example.client.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.auth.UserRole
import com.example.client.ui.navigation.bottomNavItems
import com.example.client.ui.theme.CBOCollectorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The bottom bar from the demo: one tab per destination, the current one marked, a tap switches. */
@RunWith(AndroidJUnit4::class)
class BottomNavBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertShowsTabsOf(role: UserRole) {
        val items = role.bottomNavItems()
        composeRule.setContent { CBOCollectorTheme { BottomNavBar(items, currentRoute = items.last().route, onNavigate = {}) } }

        items.forEach { composeRule.onNodeWithTag(bottomNavTag(it.route)).assertIsDisplayed() }
        composeRule.onNodeWithTag(bottomNavTag(items.last().route)).assertIsSelected()
        composeRule.onNodeWithTag(bottomNavTag(items.first().route)).assertIsNotSelected()
    }

    @Test
    fun theCollectorsBar_showsItsTabs_withTheCurrentOneSelected() = assertShowsTabsOf(UserRole.CBO_COLLECTION)

    @Test
    fun theVettingOfficersBar_showsItsTabs_withTheCurrentOneSelected() = assertShowsTabsOf(UserRole.VETTING)

    @Test
    fun theAdminsBar_showsItsTabs_withTheCurrentOneSelected() = assertShowsTabsOf(UserRole.ADMIN)

    @Test
    fun tappingATab_asksToGoThere() {
        val items = UserRole.CBO_COLLECTION.bottomNavItems()
        val tapped = mutableListOf<String>()
        composeRule.setContent { CBOCollectorTheme { BottomNavBar(items, currentRoute = items.first().route, onNavigate = { tapped += it.route }) } }

        items.forEach { composeRule.onNodeWithTag(bottomNavTag(it.route)).performClick() }

        assertEquals(items.map { it.route }, tapped)
    }
}
