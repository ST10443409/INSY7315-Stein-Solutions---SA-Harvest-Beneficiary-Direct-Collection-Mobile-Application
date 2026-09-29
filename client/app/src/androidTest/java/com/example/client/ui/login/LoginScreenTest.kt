package com.example.client.ui.login

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state = mutableStateOf(LoginUiState())
    private var submitted = 0

    private fun show() {
        composeRule.setContent {
            LoginScreen(
                state = state.value,
                onUsernameChange = { state.value = state.value.copy(username = it) },
                onPasswordChange = { state.value = state.value.copy(password = it) },
                onSubmit = { submitted++ }
            )
        }
    }

    @Test
    fun submitIsDisabled_untilBothFieldsHaveText() {
        show()
        composeRule.onNodeWithTag(LoginTags.SUBMIT).assertIsNotEnabled()

        composeRule.onNodeWithTag(LoginTags.USERNAME).performTextInput("agent")
        composeRule.onNodeWithTag(LoginTags.SUBMIT).assertIsNotEnabled()

        composeRule.onNodeWithTag(LoginTags.PASSWORD).performTextInput("pw")
        composeRule.onNodeWithTag(LoginTags.SUBMIT).assertIsEnabled()
    }

    @Test
    fun invalidCredentials_showsAnInlineError() {
        state.value = LoginUiState(username = "a", password = "b", error = LoginError.INVALID_CREDENTIALS)
        show()

        composeRule.onNodeWithTag(LoginTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.ERROR).assertTextContains("Incorrect username or password", substring = true)
    }

    @Test
    fun networkError_showsAnInlineError() {
        state.value = LoginUiState(username = "a", password = "b", error = LoginError.NETWORK)
        show()

        composeRule.onNodeWithTag(LoginTags.ERROR).assertTextContains("Can't reach the server", substring = true)
    }

    @Test
    fun expiredSession_showsTheNotice() {
        state.value = LoginUiState(sessionExpired = true)
        show()

        composeRule.onNodeWithTag(LoginTags.SESSION_EXPIRED).assertIsDisplayed()
    }
}
