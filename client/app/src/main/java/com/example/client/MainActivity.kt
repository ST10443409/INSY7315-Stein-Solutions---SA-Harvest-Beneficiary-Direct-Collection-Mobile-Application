package com.example.client

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.client.auth.RoleProvider
import com.example.client.sync.SyncScheduler
import com.example.client.ui.login.LoginRoute
import com.example.client.ui.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var roleProvider: RoleProvider

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Send anything still waiting whenever the app comes to the foreground with someone signed in, and straight after
        // a sign-in. A session that expired in the field (the workers send nothing while signed out) would otherwise
        // leave its records waiting for the next periodic run, up to 15 minutes after signing back in (#55).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                roleProvider.currentRole.filterNotNull().collect {
                    syncScheduler.syncCboCollectionsNow()
                    syncScheduler.syncVettingDecisionsNow()
                }
            }
        }

        setContent {
            val role by roleProvider.currentRole.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(role) { LoginRoute() }
                }
            }
        }
    }
}
