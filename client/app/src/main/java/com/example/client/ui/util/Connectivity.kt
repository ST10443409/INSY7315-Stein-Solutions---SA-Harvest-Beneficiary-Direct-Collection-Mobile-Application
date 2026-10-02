package com.example.client.ui.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// Ported from the CBO Collector design demo (Connectivity.kt).

private fun ConnectivityManager.hasInternetNow(): Boolean {
    val caps = getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Tracks real device connectivity so the offline-first screens reflect the actual network state. */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var online by remember { mutableStateOf(cm.hasInternetNow()) }

    DisposableEffect(cm) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = cm.hasInternetNow()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = cm.hasInternetNow()
            }
        }
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    return online
}
