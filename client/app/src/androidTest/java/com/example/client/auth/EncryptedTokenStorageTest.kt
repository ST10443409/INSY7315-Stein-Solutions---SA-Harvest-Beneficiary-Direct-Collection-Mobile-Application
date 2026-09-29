package com.example.client.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedTokenStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val token = "eyJhbGciOiJIUzI1NiJ9.super-secret-payload.signature"

    private fun newStorage() = EncryptedTokenStorage(context)

    private fun prefsFile() =
        File(context.applicationInfo.dataDir, "shared_prefs/${EncryptedTokenStorage.FILE_NAME}.xml")

    @Before
    @After
    fun wipe() = newStorage().clear()

    @Test
    fun savedSession_isRestoredByANewInstance_likeAfterProcessRestart() {
        newStorage().save(Session(token, UserRole.VETTING))

        val restored = newStorage().load()

        assertNotNull(restored)
        assertEquals(token, restored?.token)
        assertEquals(UserRole.VETTING, restored?.role)
    }

    @Test
    fun tokenAndRole_areNotVisibleInPlaintextOnDisk() {
        newStorage().save(Session(token, UserRole.ADMIN))

        val file = prefsFile()
        assertTrue("prefs file should exist", file.exists())
        val raw = file.readText()
        assertFalse("token must not appear in plaintext", raw.contains(token))
        assertFalse("token payload must not appear in plaintext", raw.contains("super-secret-payload"))
        assertFalse("role must not appear in plaintext", raw.contains("ADMIN"))
    }

    @Test
    fun clear_removesTheSession() {
        val storage = newStorage()
        storage.save(Session(token, UserRole.CBO_COLLECTION))

        storage.clear()

        assertNull(newStorage().load())
    }
}
