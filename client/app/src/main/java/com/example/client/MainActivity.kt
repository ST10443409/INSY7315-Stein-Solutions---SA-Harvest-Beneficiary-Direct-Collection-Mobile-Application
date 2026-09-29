package com.example.client

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.client.data.AppDatabase
import com.example.client.data.SyncPayload
import com.example.client.network.RetrofitClient
import com.example.client.network.SyncRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set basic layout if available, for now just logic
        // setContentView(R.layout.activity_main)

        lifecycleScope.launch {
            // 1. Simulate saving data while offline
            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.syncPayloadDao()
            
            val newPayload = SyncPayload(
                id = UUID.randomUUID().toString(),
                data = "Sample offline data"
            )
            
            dao.insert(newPayload)
            Log.d(TAG, "Inserted payload into local DB: ${newPayload.id}")

            // 2. Simulate coming online and syncing
            syncDataWithServer()
        }
    }

    private suspend fun syncDataWithServer() {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.syncPayloadDao()

            val unsyncedPayloads = dao.getUnsyncedPayloads()
            for (payload in unsyncedPayloads) {
                try {
                    val request = SyncRequest(id = payload.id, data = payload.data)
                    val response = RetrofitClient.instance.syncData(request)
                    
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully synced payload: ${payload.id}")
                        dao.markAsSynced(payload.id)
                    } else {
                        Log.e(TAG, "Failed to sync payload: ${payload.id}, Code: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing payload: ${payload.id}", e)
                }
            }
        }
    }
}
