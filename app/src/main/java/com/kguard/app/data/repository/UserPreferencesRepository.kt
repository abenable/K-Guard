package com.kguard.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kguard.app.data.model.AgeProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private val AGE_PROFILE_KEY = stringPreferencesKey("selected_age_profile")
    private val IS_CHILD_DEVICE_KEY = booleanPreferencesKey("is_child_device")
    private val ACTIVE_CHILD_ID_KEY = stringPreferencesKey("active_child_id")
    private val PARENT_PIN_HASH_KEY = stringPreferencesKey("parent_pin_hash")
    private val PARENT_PIN_SALT_KEY = stringPreferencesKey("parent_pin_salt")

    companion object {
        // PBKDF2 parameters for the parent PIN (the uninstall-protection credential).
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PIN_LENGTH_BYTES = 256 / 8
    }

    val ageProfileFlow: Flow<AgeProfile> = context.dataStore.data.map { preferences ->
        val profileId = preferences[AGE_PROFILE_KEY]
        AgeProfile.fromId(profileId)
    }

    val isChildDeviceFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_CHILD_DEVICE_KEY] ?: false
    }

    val activeChildIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ACTIVE_CHILD_ID_KEY]
    }

    val parentPinHashFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PARENT_PIN_HASH_KEY]
    }

    /**
     * Hashes [pin] with PBKDF2-HMAC-SHA256 under a fresh random salt and stores
     * salt and digest separately. The raw PIN is never persisted.
     */
    suspend fun saveParentPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        context.dataStore.edit { preferences ->
            preferences[PARENT_PIN_SALT_KEY] = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
            preferences[PARENT_PIN_HASH_KEY] = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
        }
    }

    /** Constant-time comparison of [pin] against the stored PBKDF2 hash. */
    suspend fun verifyParentPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val prefs = runCatching {
            context.dataStore.data.first()
        }.getOrNull() ?: return@withContext false
        val saltB64 = prefs[PARENT_PIN_SALT_KEY] ?: return@withContext false
        val hashB64 = prefs[PARENT_PIN_HASH_KEY] ?: return@withContext false
        val salt = runCatching {
            android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return@withContext false
        val expected = runCatching {
            android.util.Base64.decode(hashB64, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return@withContext false
        val candidate = pbkdf2(pin, salt)
        constantTimeEquals(expected, candidate)
    }

    /** True when a parent PIN has been configured on this device. */
    suspend fun hasParentPin(): Boolean {
        val prefs = runCatching {
            context.dataStore.data.first()
        }.getOrNull() ?: return false
        return prefs[PARENT_PIN_SALT_KEY] != null && prefs[PARENT_PIN_HASH_KEY] != null
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PIN_LENGTH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    suspend fun saveAgeProfile(ageProfile: AgeProfile) {
        context.dataStore.edit { preferences ->
            preferences[AGE_PROFILE_KEY] = ageProfile.id
        }
    }

    suspend fun saveChildDeviceMode(isChild: Boolean, childId: String? = null) {
        context.dataStore.edit { preferences ->
            preferences[IS_CHILD_DEVICE_KEY] = isChild
            if (childId != null) {
                preferences[ACTIVE_CHILD_ID_KEY] = childId
            } else if (!isChild) {
                preferences.remove(ACTIVE_CHILD_ID_KEY)
            }
        }
    }
}
