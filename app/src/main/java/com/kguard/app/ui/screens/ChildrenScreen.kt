package com.kguard.app.ui.screens

import com.kguard.app.ui.components.ChildAvatar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.CategoryRule
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.model.SafetyCategory
import com.kguard.app.data.repository.ChildPhotoRepository
import com.kguard.app.data.repository.ScreenTimeRepository
import com.kguard.app.service.KGuardMessagingService
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGEmptyState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGToggleRow
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.components.PhoneLockDialog
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.SessionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Lists child profiles; the selected one drives which age rules apply in the checkers. */
@Composable
fun ChildrenScreen(
    session: SessionViewModel,
    onEditChild: (String?) -> Unit,
    onNavigateBack: () -> Unit
) {
    val children by session.children.collectAsState()
    val selected by session.selectedChild.collectAsState()
    val familyId by session.familyId.collectAsState()

    ChildrenContent(
        children = children,
        selected = selected,
        familyId = familyId,
        onSelectChild = { session.selectChild(it) },
        onEditChild = onEditChild,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun ChildrenContent(
    children: List<ChildProfile>,
    selected: ChildProfile?,
    familyId: String?,
    onSelectChild: (String) -> Unit,
    onEditChild: (String?) -> Unit,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var lockDialogFor by remember { mutableStateOf<ChildProfile?>(null) }
    var lockError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { KGuardTopAppBar("Children & Protection", canNavigateBack = true, onNavigateBack = onNavigateBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEditChild(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add child") }
            )
        }
    ) { padding ->
        when {
            children.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                KGEmptyState(
                    icon = Icons.Default.Person,
                    title = "No child profiles yet",
                    description = "Add a child to set their age group and choose which categories " +
                        "K-Guard should block, blur or warn about.",
                    action = { KGPrimaryButton("Add a child", onClick = { onEditChild(null) }) }
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                lockError?.let { msg ->
                    item(key = "lock-error") {
                        Text(msg, color = KGColors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(children, key = { it.id }) { child ->
                    val isSelected = child.id == selected?.id
                    // Only a paired phone can be locked; its real lock state streams from Firestore.
                    val fid = familyId
                    val phoneLocked = if (fid != null && child.linkedUid != null) {
                        val device by remember(fid, child.id) {
                            ScreenTimeRepository.device(fid, child.id, child.name)
                        }.collectAsState(initial = null)
                        device?.screenLock?.isLocked ?: false
                    } else null
                    ChildRowCard(
                        child = child,
                        isSelected = isSelected,
                        phoneLocked = phoneLocked,
                        onPhoneLockChange = { lock ->
                            if (fid == null) return@ChildRowCard
                            if (lock) lockDialogFor = child
                            else scope.launch {
                                lockError = null
                                try {
                                    ScreenTimeRepository.unlock(fid, child.id, child.name, 0)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    lockError = "Couldn't unlock ${child.name}'s phone: ${e.message ?: "please try again."}"
                                }
                            }
                        },
                        onClick = {
                            // Selecting the child keeps it active in the checkers; the row then
                            // opens its editor, replacing the old separate Edit affordance.
                            onSelectChild(child.id)
                            onEditChild(child.id)
                        }
                    )
                }
            }
        }
    }

    lockDialogFor?.let { child ->
        PhoneLockDialog(
            childName = child.name,
            onDismiss = { lockDialogFor = null },
            onConfirm = { startsIn, duration ->
                lockDialogFor = null
                val fid = familyId ?: return@PhoneLockDialog
                scope.launch {
                    lockError = null
                    try {
                        ScreenTimeRepository.lock(
                            fid, child.id, child.name, "Your parent has locked this phone.",
                            duration, startsIn.takeIf { it > 0 }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lockError = "Couldn't lock ${child.name}'s phone: ${e.message ?: "please try again."}"
                    }
                }
            }
        )
    }
}

@Composable
private fun ChildRowCard(
    child: ChildProfile,
    isSelected: Boolean,
    phoneLocked: Boolean?,
    onPhoneLockChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    KGCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChildAvatar(child = child, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(child.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${child.age.title} · ${child.age.ageRange}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isSelected) KGStatusTag("Active", KGColors.info)
                if (child.linkedUid != null) {
                    KGStatusTag("Paired", KGColors.success)
                } else {
                    KGStatusTag("Not linked", KGColors.warning)
                }
            }
        }
        // null = phone not paired, so there's nothing to lock.
        if (phoneLocked != null) {
            Spacer(Modifier.height(4.dp))
            KGToggleRow(
                title = "Lock phone",
                description = if (phoneLocked) "Locked. Turn off to unlock ${child.name}'s phone."
                else "Lock ${child.name}'s phone now or later.",
                checked = phoneLocked,
                onCheckedChange = onPhoneLockChange
            )
        }
    }
}

/** Create or edit a child: name (required), optional photo from the device gallery, age group, per-category rules and reveal permission. Child devices pair anonymously, so no email is collected. */
@Composable
fun ChildEditorScreen(
    session: SessionViewModel,
    childId: String?,
    onNavigateBack: () -> Unit
) {
    val children by session.children.collectAsState()
    val busy by session.busy.collectAsState()
    val error by session.error.collectAsState()
    val pairingCode by session.pairingCode.collectAsState()
    val existing = children.find { it.id == childId }

    ChildEditorContent(
        existing = existing,
        childId = childId,
        busy = busy,
        error = error,
        pairingCode = pairingCode,
        onGenerateCode = { id -> session.createPairingCode(id) },
        onClearCode = { session.clearPairingCode() },
        onSaveChild = { updatedChild, newPhoto, onSaved ->
            val isNew = existing == null
            session.saveChild(updatedChild, newPhoto) { id ->
                // New child: the pairing code is generated at once so the parent never has to come
                // back and press "Generate pairing code" as a second step.
                if (isNew) session.createPairingCode(id)
                onSaved()
            }
        },
        onDeleteChild = { id ->
            session.deleteChild(id, onNavigateBack)
        },
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun ChildEditorContent(
    existing: ChildProfile?,
    childId: String? = null,
    busy: Boolean,
    error: String?,
    pairingCode: String? = null,
    onGenerateCode: (String) -> Unit = {},
    onClearCode: () -> Unit = {},
    /** The profile plus a freshly picked photo (prepared JPEG bytes, uploaded on save). [onSaved]
     *  runs after the profile (and optional photo) is safely stored, for post-save UI decisions. */
    onSaveChild: (ChildProfile, ByteArray?, () -> Unit) -> Unit,
    onDeleteChild: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // Editing an existing child requires the children stream to have actually emitted: before that
    // `existing` is null and a naive Save would create a DUPLICATE child. Block the whole form (and
    // therefore all input) until the record is loaded, so form state is only ever keyed/seeded from
    // the resolved child and typed input is never wiped by a late emission.
    val childrenLoaded = childId == null || existing != null
    if (!childrenLoaded) {
        Scaffold(
            topBar = { KGuardTopAppBar("Loading…", canNavigateBack = true, onNavigateBack = onNavigateBack) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    val photoUrl = existing?.photoUrl ?: ""
    var newPhoto by remember(existing?.id) { mutableStateOf<ByteArray?>(null) }
    var preparingPhoto by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var ageId by remember(existing?.id) { mutableStateOf(existing?.ageProfile ?: AgeProfile.PRETEENS_8_12.id) }
    var rules by remember(existing?.id) { mutableStateOf(existing?.categoryRules ?: emptyMap()) }
    var allowReveal by remember(existing?.id) { mutableStateOf(existing?.allowReveal ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** True right after Save created a brand-new child, so the auto-shown pairing dialog returns to the list on Done. */
    var justSavedNewChild by remember { mutableStateOf(false) }
    val age = AgeProfile.fromId(ageId)
    val revealAllowed = age == AgeProfile.TEENS_13_17 || age == AgeProfile.ADULTS_18_PLUS

    // Android's photo picker (no storage permission). The photo is squared/resized now, shown as a
    // preview, and uploaded on Save so it appears on every phone in the family.
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                preparingPhoto = true
                photoError = null
                try {
                    newPhoto = ChildPhotoRepository.prepare(context, uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    photoError = "That photo couldn't be used. Try another one."
                } finally {
                    preparingPhoto = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                if (existing == null) "Add child" else "Edit ${existing.name}",
                canNavigateBack = true, onNavigateBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
        ) {
            // -------------------------------------------------------------- Photo
            KGSectionHeader("Photo", hint = "A face your child recognises. Optional.")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(KGShapes.circle)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = !preparingPhoto) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (preparingPhoto) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    } else if (newPhoto != null || photoUrl.isNotBlank()) {
                        AsyncImage(
                            model = newPhoto ?: photoUrl,
                            contentDescription = "Child Avatar",
                            modifier = Modifier.fillMaxSize().clip(KGShapes.circle),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Pick Photo",
                            tint = KGColors.inkMuted,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            Text(
                when {
                    preparingPhoto -> "Preparing photo…"
                    newPhoto != null -> "New photo · saved when you tap Save"
                    else -> "Tap to pick photo from device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            photoError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            // -------------------------------------------------------------- Name
            KGSectionHeader("Name", hint = "How this child appears across K-Guard.")
            KGTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = "Child's name",
                isError = name.isBlank(),
                supportingText = "Required. Everything else on this form is optional."
            )

            // -------------------------------------------------------------- Age group
            KGSectionHeader("Age group", hint = "Sets the default protection level for every category below.")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgeProfile.entries.forEach { p ->
                    val isChosen = p.id == ageId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isChosen) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                KGShapes.cardSmall
                            )
                            .selectable(selected = isChosen, role = Role.RadioButton) { ageId = p.id }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isChosen, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${p.title} (${p.ageRange})", style = MaterialTheme.typography.titleSmall)
                            Text(
                                p.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = KGColors.inkMuted
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------- Web categories
            KGSectionHeader(
                "Web categories",
                hint = "Per-category overrides. \"Default\" follows the age group; rules can make " +
                    "K-Guard stricter. They can relax it only for lower-risk categories and moderate " +
                    "scores. Sexual content, threats and self-harm are always handled strictly."
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SafetyCategory.entries.forEach { cat ->
                    KGCard(Modifier.fillMaxWidth()) {
                        Text(cat.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            cat.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = KGColors.inkMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val current = CategoryRule.fromId(rules[cat.id])
                            FilterChip(
                                selected = current == null,
                                onClick = { rules = rules - cat.id },
                                label = { Text("Default") }
                            )
                            CategoryRule.entries
                                .filter { !(cat.lockedAgainstRelaxing && it != CategoryRule.BLOCK) }
                                .forEach { r ->
                                    FilterChip(
                                        selected = current == r,
                                        onClick = { rules = rules + (cat.id to r.id) },
                                        label = { Text(r.label) }
                                    )
                                }
                        }
                    }
                }
            }

            // -------------------------------------------------------------- Reveal permission
            KGSectionHeader("Revealing blurred content")
            KGToggleRow(
                title = "Allow tap-to-reveal on blurred items",
                description = if (!revealAllowed)
                    "Not available for this age group; blurred items stay hidden."
                else "Lets this child choose to view blurred items.",
                checked = allowReveal && revealAllowed,
                onCheckedChange = { if (revealAllowed) allowReveal = it },
                modifier = Modifier.fillMaxWidth().alpha(if (revealAllowed) 1f else 0.6f)
            )

            // -------------------------------------------------------------- Device linking
            KGSectionHeader("Device linking", hint = "Pair your child's phone so K-Guard protects it directly.")
            if (existing != null) {
                KGCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Link status", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (existing.linkedUid != null)
                                    "A device is paired with this child's Google account."
                                else
                                    "No device is paired yet. Generate a code and enter it on your child's phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = KGColors.inkMuted
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        if (existing.linkedUid != null) {
                            KGStatusTag("Paired", KGColors.success)
                        } else {
                            KGStatusTag("Not linked", KGColors.warning)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    KGSecondaryButton(
                        text = "Generate pairing code",
                        onClick = { onGenerateCode(existing.id) },
                        leading = { Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                KGCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Save the profile first — pairing codes are generated once the child exists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KGColors.inkMuted
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = KGColors.danger, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            KGPrimaryButton(
                text = if (busy) "Saving…" else "Save",
                onClick = {
                    justSavedNewChild = childId == null
                    onSaveChild(
                        (existing ?: ChildProfile()).copy(
                            name = name, email = existing?.email ?: "", photoUrl = photoUrl, ageProfile = ageId, categoryRules = rules,
                            allowReveal = allowReveal && (age == AgeProfile.TEENS_13_17 || age == AgeProfile.ADULTS_18_PLUS)
                        ),
                        newPhoto
                    ) {
                        // Editing: go back as before. A new child stays here so the automatically
                        // generated pairing code is seen at once; its dialog returns to the list.
                        if (childId != null) onNavigateBack()
                    }
                },
                enabled = name.isNotBlank() && !busy && !preparingPhoto,
                modifier = Modifier.fillMaxWidth()
            )

            if (existing != null) {
                KGDangerButton(
                    text = "Delete profile",
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pairingCode?.let { code ->
        LaunchedEffect(code) {
            KGuardMessagingService.showAlert(
                context,
                "Device Pairing OTP Code",
                "Your 6-digit device pairing code is: $code. Enter this on your child's phone during setup.",
                "children"
            )
        }
        AlertDialog(
            onDismissRequest = { onClearCode(); if (justSavedNewChild) onNavigateBack() },
            title = { Text("6-Digit Device Pairing OTP") },
            text = {
                Column {
                    Text(
                        "Enter this code on your child's phone during setup to pair their device:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    KGCard(Modifier.fillMaxWidth()) {
                        Text(
                            code,
                            style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This code expires in 15 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KGColors.inkMuted
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onClearCode(); if (justSavedNewChild) onNavigateBack() }) { Text("Done") } }
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("The profile and its rules are removed. Past alerts stay in the review queue until they expire.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteChild(existing.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ChildrenScreenPreview() {
    val child = ChildProfile(id = "1", name = "Alex", email = "alex@example.com", ageProfile = AgeProfile.PRETEENS_8_12.id)
    KGuardTheme {
        ChildrenContent(
            children = listOf(child),
            selected = child,
            familyId = "family_1",
            onSelectChild = {},
            onEditChild = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ChildEditorScreenPreview() {
    val child = ChildProfile(id = "1", name = "Alex", email = "alex@example.com", ageProfile = AgeProfile.PRETEENS_8_12.id)
    KGuardTheme {
        ChildEditorContent(
            existing = child,
            busy = false,
            error = null,
            onSaveChild = { _, _ -> },
            onDeleteChild = {},
            onNavigateBack = {}
        )
    }
}
