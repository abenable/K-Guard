package com.kguard.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseUser
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.model.FamilyPrivacy
import com.kguard.app.data.model.ParentAction
import com.kguard.app.data.model.SafetyEvent
import com.kguard.app.data.repository.ChildPhotoRepository
import com.kguard.app.data.repository.FamilyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds the signed-in parent/child, their family, children, safety events and privacy settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel : ViewModel() {

    private val repo = FamilyRepository

    private val _user = MutableStateFlow(repo.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    val signedIn: StateFlow<Boolean> = _user.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.currentUser != null)

    private val _authChecked = MutableStateFlow(false)
    val authChecked: StateFlow<Boolean> = _authChecked.asStateFlow()

    private val _familyId = MutableStateFlow<String?>(null)
    val familyId: StateFlow<String?> = _familyId.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedChildId = MutableStateFlow<String?>(null)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    // Set once redeemPairingCode succeeds in this session; the auth listener's ensureFamily()
    // must then never overwrite _familyId (it resolves after pairing and would clobber it).
    private var pairedDevice = false

    /** Child devices are anonymous identities (parents sign in with Google only). */
    val isChildDevice: StateFlow<Boolean> = _user.map { it?.isAnonymous == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.currentUser?.isAnonymous == true)

    /** Parent: every child in the family. Child device: only its own profile (all the rules allow it to read). */
    val children: StateFlow<List<ChildProfile>> = combine(_familyId, _user) { id, u -> id to u }
        .flatMapLatest { (id, u) ->
            when {
                id == null || u == null -> flowOf(emptyList())
                u.isAnonymous -> repo.linkedChild(id, u.uid).resilient(emptyList())
                else -> repo.children(id).resilient(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Safety alerts and privacy settings are for parents; a child device is not allowed to read them.
    val events: StateFlow<List<SafetyEvent>> = combine(_familyId, isChildDevice) { id, child -> id to child }
        .flatMapLatest { (id, child) -> if (id == null || child) flowOf(emptyList()) else repo.events(id).resilient(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val privacy: StateFlow<FamilyPrivacy> = combine(_familyId, isChildDevice) { id, child -> id to child }
        .flatMapLatest { (id, child) -> if (id == null || child) flowOf(FamilyPrivacy()) else repo.privacy(id).resilient(FamilyPrivacy()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FamilyPrivacy())

    /** The child currently being protected/tested; defaults to the first child. */
    val selectedChild: StateFlow<ChildProfile?> = combine(children, _selectedChildId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Badge count from a dedicated open-events listener, so old OPEN alerts outside the 100-event window still count. */
    val openAlertCount: StateFlow<Int> = combine(_familyId, isChildDevice) { id, child -> id to child }
        .flatMapLatest { (id, child) ->
            if (id == null || child) flowOf(emptyList<SafetyEvent>()) else repo.openEvents(id).resilient(emptyList())
        }
        .map { list -> list.count { it.isOpen } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch {
            repo.authState().collect { u ->
                _user.value = u
                _authChecked.value = true
                if (u == null) {
                    _familyId.value = null
                    _selectedChildId.value = null
                } else {
                    // After a successful pairing, ensureFamily (already in flight or fired here)
                    // must not overwrite the familyId the pairing code resolved to. Anonymous
                    // identities are child devices by design — they never own a family.
                    if (!pairedDevice && u.isAnonymous != true) {
                        runCatching { repo.ensureFamily() }
                            .onSuccess { if (!pairedDevice) _familyId.value = it }
                            .onFailure { reportError(it) }
                    }
                    runCatching { repo.registerPushToken() }
                }
            }
        }
    }

    fun selectChild(id: String) { _selectedChildId.value = id }
    fun clearError() { _error.value = null }
    fun clearPairingCode() { _pairingCode.value = null }

    /** The single sign-in path: a Google credential from Credential Manager. */
    fun signInWithCredential(credential: AuthCredential, onDone: () -> Unit = {}) = launchBusy {
        repo.signInWithCredential(credential)
        onDone() // only after success; failures surface through [error]
    }

    /**
     * Pair a child device without any account: sign in anonymously if needed, then redeem the
     * parent's 6-digit code, which binds the child profile to this device's identity.
     */
    fun pairChildDevice(code: String, onDone: () -> Unit = {}) = launchBusy {
        // Flag BEFORE the anonymous sign-in fires: the auth listener would otherwise race this
        // redemption and call ensureFamily, creating a users/{uid} doc with role "parent" —
        // which the pairing function then (correctly) rejects.
        pairedDevice = true
        if (repo.currentUser == null) {
            runCatching { repo.signInAnonymously() }
                .onFailure {
                    throw IllegalStateException(
                        "Could not create a device identity. Ask the parent to enable Anonymous sign-in for K-Guard in the Firebase console.",
                        it
                    )
                }
        }
        val res = repo.redeemPairingCode(code)
        val fid = res["familyId"] as? String ?: error("Pairing response is missing the family id.")
        val cid = res["childId"] as? String
        _familyId.value = fid
        if (cid != null) _selectedChildId.value = cid
        onDone() // only once the code is accepted; a wrong or expired code stays on this screen
    }

    /** Removes this phone's push token first, so the next account on it gets no stale alerts. */
    fun signOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                runCatching { repo.unregisterPushToken() }
            } finally {
                // The auth listener clears the rest when the user is nulled.
                pairedDevice = false
                _pairingCode.value = null
                _error.value = null
                _selectedChildId.value = null
                repo.signOut()
                _busy.value = false
                onDone()
            }
        }
    }

    /**
     * Saves the profile, then uploads [newPhoto] (a JPEG from ChildPhotoRepository.prepare) so it shows on
     * every phone in the family. Only http(s) photo URLs are ever stored; a local path works on one phone only.
     */
    fun saveChild(child: ChildProfile, newPhoto: ByteArray? = null, onDone: (String) -> Unit = {}) = launchBusy {
        val fid = _familyId.value ?: error("Your family is not ready yet.")
        require(child.name.isNotBlank()) { "Please enter a name." }
        val clean = child.copy(photoUrl = child.photoUrl.takeIf { it.startsWith("http") }.orEmpty())
        val id = repo.saveChild(fid, clean)
        if (newPhoto != null) {
            val url = runCatching { ChildPhotoRepository.upload(id, newPhoto) }
                .getOrElse { throw IllegalStateException("The profile was saved, but the photo couldn't be uploaded. Check the connection and try again.") }
            repo.updateChildPhoto(fid, id, url)
        }
        if (_selectedChildId.value == null) _selectedChildId.value = id
        onDone(id)
    }

    fun deleteChild(childId: String, onDone: () -> Unit = {}) = launchBusy {
        val fid = _familyId.value ?: error("Your family is not ready yet.")
        try {
            repo.deleteChild(fid, childId)
        } finally {
            // The dialog must close even when the delete fails (the error is surfaced by launchBusy).
            if (_selectedChildId.value == childId) _selectedChildId.value = null
            onDone()
        }
    }

    fun createPairingCode(childId: String) = launchBusy { _pairingCode.value = repo.createPairingCode(childId) }
    fun sendChildOtp(childId: String) = createPairingCode(childId)

    fun review(eventId: String, action: ParentAction, note: String) = launchBusy {
        val fid = _familyId.value ?: error("Your family is not ready yet.")
        repo.reviewEvent(fid, eventId, action, note)
    }

    fun savePrivacy(p: FamilyPrivacy) = launchBusy {
        val fid = _familyId.value ?: error("Your family is not ready yet.")
        repo.savePrivacy(fid, p)
    }

    /**
     * The repository flows close with the Firestore error, so without a retry a transient
     * failure would permanently kill the inner listener until familyId changes. Re-subscribe
     * with exponential backoff (1s, 2s, … capped at 30s); reportError stays for visibility.
     */
    private fun <T> Flow<T>.resilient(default: T): Flow<T> =
        retryWhen { cause, attempt ->
            reportError(cause)
            delay(minOf(30_000L, 1_000L shl attempt.coerceAtMost(5L).toInt()))
            true
        }.catch { reportError(it); emit(default) }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try { block() } catch (e: Exception) { reportError(e) } finally { _busy.value = false }
        }
    }

    private fun reportError(t: Throwable) {
        _error.value = t.localizedMessage ?: "Something went wrong. Please try again."
    }
}
