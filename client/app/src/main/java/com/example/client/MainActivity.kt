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
import com.example.client.auth.RoleProvider
import com.example.client.sync.SyncScheduler
import com.example.client.ui.login.LoginRoute
import com.example.client.ui.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var roleProvider: RoleProvider

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override fun onStart() {
        super.onStart()
        // Back in the foreground: send anything still waiting to sync.
        syncScheduler.syncCboCollectionsNow()
        syncScheduler.syncVettingDecisionsNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
