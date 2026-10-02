package com.example.client.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.client.R

object LoginTags {
    const val USERNAME = "login_username"
    const val PASSWORD = "login_password"
    const val SUBMIT = "login_submit"
    const val ERROR = "login_error"
    const val SESSION_EXPIRED = "login_session_expired"
}

/** Hilt-backed entry point for the login screen. */
@Composable
fun LoginRoute(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    LoginScreen(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::onSubmit
    )
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)

        if (state.sessionExpired) {
            Text(
                stringResource(R.string.login_session_expired),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(LoginTags.SESSION_EXPIRED)
            )
        }

        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.login_username)) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LoginTags.USERNAME)
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !state.isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LoginTags.PASSWORD)
        )

        state.error?.let { error ->
            Text(
                text = stringResource(error.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag(LoginTags.ERROR)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LoginTags.SUBMIT)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.login_submit))
            }
        }
    }
}

private fun LoginError.messageRes(): Int = when (this) {
    LoginError.INVALID_CREDENTIALS -> R.string.login_error_invalid_credentials
    LoginError.NETWORK -> R.string.login_error_network
    LoginError.SERVER -> R.string.login_error_server
    LoginError.TOO_MANY_ATTEMPTS -> R.string.login_error_too_many_attempts
}
