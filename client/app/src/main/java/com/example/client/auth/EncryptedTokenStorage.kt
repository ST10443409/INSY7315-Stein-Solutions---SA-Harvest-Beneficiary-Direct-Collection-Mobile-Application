package com.example.client.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TokenStorage] backed by [EncryptedSharedPreferences]: keys and values are AES-encrypted
 * with a keyset wrapped by a hardware-backed Android Keystore master key.
 *
 * The prefs file names are excluded from backup (see `res/xml/backup_rules.xml`), because
 * the Keystore key does not travel with a backup and a restored file could not be decrypted.
 */
@Singleton
class EncryptedTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenStorage {

    private val prefs: SharedPreferences by lazy { openPrefs() }

    override fun load(): Session? = try {
        val token = prefs.getString(KEY_TOKEN, null)
        val role = prefs.getString(KEY_ROLE, null)?.let { name ->
            UserRole.values().firstOrNull { it.name == name }
        }
        if (token.isNullOrBlank() || role == null) null else Session(token, role, prefs.getString(KEY_CBO_ID, null))
    } catch (e: Exception) {
        // Unreadable (e.g. Keystore key invalidated): treat as signed out and start clean.
        clear()
        null
    }

    override fun save(session: Session) {
        // commit(): the session must be durable even if the process dies right after login.
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_ROLE, session.role.name)
            .putString(KEY_CBO_ID, session.cboId)
            .commit()
    }

    override fun clear() {
        try {
            prefs.edit().clear().commit()
        } catch (e: Exception) {
            context.deleteSharedPreferences(FILE_NAME)
        }
    }

    private fun openPrefs(): SharedPreferences = try {
        create()
    } catch (e: GeneralSecurityException) {
        recreate()
    } catch (e: IOException) {
        recreate()
    }

    private fun recreate(): SharedPreferences {
        context.deleteSharedPreferences(FILE_NAME)
        return create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        const val FILE_NAME = "auth_secure_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_ROLE = "role"
        private const val KEY_CBO_ID = "cbo_id"
    }
}
