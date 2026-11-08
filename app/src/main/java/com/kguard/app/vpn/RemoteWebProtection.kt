package com.kguard.app.vpn

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** What a parent sets for one child's web protection. Age group and category rules live on the child profile. */
data class WebSettings(
    val enabled: Boolean = false,
    val customBlock: Set<String> = emptySet(),
    val customAllow: Set<String> = emptySet(),
    val educationalAllow: Boolean = true,
    /** The on-device AI classifier judges sites that are on no list. */
    val aiFilter: Boolean = true
)

/** What the child's phone reports back, so the parent sees the real state rather than the requested one. */
data class WebDeviceStatus(
    /** The DNS filter is running on the child's phone. */
    val running: Boolean = false,
    /** Android needs a one-time tap on the child's phone before K-Guard may filter. */
    val needsConsent: Boolean = false,
    /** Protection was on and was switched off on the phone (for example from Android's VPN settings). */
    val turnedOffOnDevice: Boolean = false,
    val updatedAtMillis: Long? = null
)

data class RemoteWebState(
    val settings: WebSettings = WebSettings(),
    val device: WebDeviceStatus = WebDeviceStatus(),
    /** False until the first snapshot arrives, so the UI does not flash "off". */
    val loaded: Boolean = false
)

/**
 * Web protection settings shared between the parent's phone and the child's phone through
 * `families/{familyId}/webProtection/{childId}`. The parent writes the settings; the child's phone
 * listens, applies them to its DNS filter, and writes back only the `device` status.
 */
object RemoteWebProtection {
    private const val TAG = "KGuardWeb"
    private val db get() = FirebaseFirestore.getInstance()

    private fun doc(familyId: String, childId: String) = db.document("families/$familyId/webProtection/$childId")

    fun observe(familyId: String, childId: String): Flow<RemoteWebState> = callbackFlow {
        val reg = doc(familyId, childId).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e(TAG, "web protection listener error", err)
                close(err)
                return@addSnapshotListener
            }
            trySend(parse(snap?.data))
        }
        awaitClose { reg.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parse(data: Map<String, Any?>?): RemoteWebState {
        if (data == null) return RemoteWebState(loaded = true)
        val d = data["device"] as? Map<String, Any?>
        return RemoteWebState(
            settings = WebSettings(
                enabled = data["enabled"] as? Boolean ?: false,
                customBlock = (data["customBlock"] as? List<String>)?.toSet() ?: emptySet(),
                customAllow = (data["customAllow"] as? List<String>)?.toSet() ?: emptySet(),
                educationalAllow = data["educationalAllow"] as? Boolean ?: true,
                aiFilter = data["aiFilter"] as? Boolean ?: true
            ),
            device = WebDeviceStatus(
                running = d?.get("running") as? Boolean ?: false,
                needsConsent = d?.get("needsConsent") as? Boolean ?: false,
                turnedOffOnDevice = d?.get("turnedOffOnDevice") as? Boolean ?: false,
                updatedAtMillis = (d?.get("updatedAt") as? com.google.firebase.Timestamp)?.toDate()?.time
            ),
            loaded = true
        )
    }

    // ---------------------------------------------------------------- parent writes
    private suspend fun write(familyId: String, childId: String, fields: Map<String, Any>) {
        doc(familyId, childId).set(fields + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    suspend fun setEnabled(familyId: String, childId: String, enabled: Boolean) =
        write(familyId, childId, mapOf("enabled" to enabled))

    suspend fun setEducationalAllow(familyId: String, childId: String, on: Boolean) =
        write(familyId, childId, mapOf("educationalAllow" to on))

    suspend fun setAiFilter(familyId: String, childId: String, on: Boolean) =
        write(familyId, childId, mapOf("aiFilter" to on))

    /** Adding to one list removes the domain from the other, so a site is never both blocked and allowed. */
    suspend fun addBlock(familyId: String, childId: String, domain: String) = write(
        familyId, childId,
        mapOf("customBlock" to FieldValue.arrayUnion(domain), "customAllow" to FieldValue.arrayRemove(domain))
    )

    suspend fun addAllow(familyId: String, childId: String, domain: String) = write(
        familyId, childId,
        mapOf("customAllow" to FieldValue.arrayUnion(domain), "customBlock" to FieldValue.arrayRemove(domain))
    )

    suspend fun removeBlock(familyId: String, childId: String, domain: String) =
        write(familyId, childId, mapOf("customBlock" to FieldValue.arrayRemove(domain)))

    suspend fun removeAllow(familyId: String, childId: String, domain: String) =
        write(familyId, childId, mapOf("customAllow" to FieldValue.arrayRemove(domain)))

    // ---------------------------------------------------------------- child writes
    /** Called on the child's phone. Firestore rules let a paired child change only the `device` field. */
    suspend fun reportDeviceStatus(familyId: String, childId: String, status: WebDeviceStatus) {
        doc(familyId, childId).set(
            mapOf(
                "device" to mapOf(
                    "running" to status.running,
                    "needsConsent" to status.needsConsent,
                    "turnedOffOnDevice" to status.turnedOffOnDevice,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ),
            SetOptions.merge()
        ).await()
    }
}

/**
 * Parent-side editor for one child's settings, used by the Web Protection screen.
 * Every change is written to Firestore and reaches the child's phone through its listener.
 */
class ParentWebEditor(
    private val scope: CoroutineScope,
    private val familyId: String,
    private val childId: String,
    private val onError: (Throwable) -> Unit
) {
    val state: StateFlow<RemoteWebState> = RemoteWebProtection.observe(familyId, childId)
        .catch { onError(it); emit(RemoteWebState(loaded = true)) }
        .stateIn(scope, SharingStarted.Eagerly, RemoteWebState())

    val customBlock: StateFlow<Set<String>> = state.map { it.settings.customBlock }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    val customAllow: StateFlow<Set<String>> = state.map { it.settings.customAllow }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    val educationalAllow: StateFlow<Boolean> = state.map { it.settings.educationalAllow }.stateIn(scope, SharingStarted.Eagerly, true)
    val aiFilter: StateFlow<Boolean> = state.map { it.settings.aiFilter }.stateIn(scope, SharingStarted.Eagerly, true)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            _saving.value = true
            try { block() } catch (e: Exception) { onError(e) } finally { _saving.value = false }
        }
    }

    fun setEnabled(on: Boolean) = launch { RemoteWebProtection.setEnabled(familyId, childId, on) }
    fun setEducationalAllow(on: Boolean) = launch { RemoteWebProtection.setEducationalAllow(familyId, childId, on) }
    fun setAiFilter(on: Boolean) = launch { RemoteWebProtection.setAiFilter(familyId, childId, on) }

    /** Returns false when [raw] is not a website name; nothing is written then. */
    fun addCustomBlock(raw: String): Boolean {
        val d = VpnConfigStore.cleanDomain(raw) ?: return false
        launch { RemoteWebProtection.addBlock(familyId, childId, d) }
        return true
    }

    fun addCustomAllow(raw: String): Boolean {
        val d = VpnConfigStore.cleanDomain(raw) ?: return false
        launch { RemoteWebProtection.addAllow(familyId, childId, d) }
        return true
    }

    fun removeCustomBlock(domain: String) = launch { RemoteWebProtection.removeBlock(familyId, childId, domain) }
    fun removeCustomAllow(domain: String) = launch { RemoteWebProtection.removeAllow(familyId, childId, domain) }
}
