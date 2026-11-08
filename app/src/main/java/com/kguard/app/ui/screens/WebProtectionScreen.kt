package com.kguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.CategoryRule
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.model.ParentAction
import com.kguard.app.data.model.SafetyCategory
import com.kguard.app.data.model.SafetyEvent
import com.kguard.app.ui.components.ChildAvatar
import com.kguard.app.ui.components.DecisionBadge
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGEmptyState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGToggleRow
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.viewmodel.SessionViewModel
import com.kguard.app.vpn.DomainFilter
import com.kguard.app.vpn.ParentWebEditor
import com.kguard.app.vpn.Source
import com.kguard.app.vpn.VpnPolicy
import java.text.DateFormat
import java.util.Date

/** A web-block alert from the child's DNS filter (written by the `reportWebBlock` function). */
private fun SafetyEvent.isWebBlock() = contentType == "web"

/**
 * Parent's phone. Every setting is saved to `families/{fid}/webProtection/{childId}` for the chosen child
 * and applied by that child's phone (see [com.kguard.app.vpn.ChildWebProtectionSync]); nothing filters here.
 */
@Composable
fun WebProtectionScreen(session: SessionViewModel, onNavigateBack: () -> Unit) {
    val children by session.children.collectAsState()
    val defaultChild by session.selectedChild.collectAsState()
    val familyId by session.familyId.collectAsState()
    val events by session.events.collectAsState()
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    val child = children.find { it.id == chosenId } ?: defaultChild

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); error = null } }

    val fid = familyId
    val cid = child?.id
    // ponytail: editors live in the screen scope, so switching children keeps the old listener until the screen closes.
    val editor = remember(fid, cid) {
        if (fid == null || cid == null) null
        else ParentWebEditor(scope, fid, cid) { error = "Couldn't save. Check the connection and try again." }
    }

    Scaffold(
        topBar = { KGuardTopAppBar("Web Protection", canNavigateBack = true, onNavigateBack = onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (editor == null || child == null) {
            KGEmptyState(
                icon = Icons.Default.Public,
                title = "No child profile yet",
                description = "Add a child profile and pair the child's phone to set up web protection.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        WebProtectionContent(
            session = session,
            editor = editor,
            child = child,
            children = children,
            onSelectChild = { chosenId = it },
            events = events.filter { it.isWebBlock() && it.childId == child.id },
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun WebProtectionContent(
    session: SessionViewModel,
    editor: ParentWebEditor,
    child: ChildProfile,
    children: List<ChildProfile>,
    onSelectChild: (String) -> Unit,
    events: List<SafetyEvent>,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableIntStateOf(0) }
    val openWeb = events.count { it.isOpen }
    val context = LocalContext.current
    val remote by editor.state.collectAsState()
    // One shared filter for the dry-run and list sizes, reloaded when the configuration it explains changes.
    val filter = remember(child.id, child.categoryRules, remote.settings) {
        runCatching { DomainFilter.load(context) }.getOrNull()
    }

    Column(modifier.fillMaxSize()) {
        if (children.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                children.forEach { c ->
                    FilterChip(
                        selected = c.id == child.id,
                        onClick = { onSelectChild(c.id) },
                        label = { Text(c.name) },
                        leadingIcon = { ChildAvatar(c, 20.dp) }
                    )
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Status") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Categories") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Activity")
                    if (openWeb > 0) Badge { Text("$openWeb") }
                }
            })
        }
        when (tab) {
            0 -> StatusTab(child, editor, filter)
            1 -> CategoriesTab(session, child, editor, filter)
            else -> ActivityTab(session, editor, events)
        }
    }
}

// ---------------------------------------------------------------- Status: on/off as reported by the child's phone + "Check a site"
@Composable
private fun StatusTab(child: ChildProfile, editor: ParentWebEditor, filter: DomainFilter?) {
    val remote by editor.state.collectAsState()
    val saving by editor.saving.collectAsState()
    val wanted = remote.settings.enabled
    val device = remote.device
    val name = child.name

    var site by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var siteBlocked by remember { mutableStateOf(false) }

    data class Hero(val headline: String, val detail: String, val color: Color)
    val hero = when {
        !remote.loaded -> Hero("Checking…", "Loading $name's settings.", KGColors.inkMuted)
        !wanted -> Hero("Web protection is off", "Turn it on to filter websites on $name's phone.", KGColors.danger)
        device.running -> Hero(
            "Web protection is on",
            "Filtering on $name's phone (${child.age.ageRange}) with this profile's age group and category rules.",
            KGColors.success
        )
        device.needsConsent -> Hero(
            "Needs one tap on $name's phone",
            "Android asks the person holding the phone to allow K-Guard once. A notification is waiting there: tap it, then OK.",
            KGColors.warning
        )
        device.turnedOffOnDevice -> Hero(
            "Turned off on $name's phone",
            "Protection was switched off on the phone. K-Guard is turning it back on.",
            KGColors.danger
        )
        device.updatedAtMillis == null -> Hero(
            "Waiting for $name's phone",
            if (child.linkedUid == null) "Protection starts as soon as $name's phone is paired and online."
            else "The setting is saved. $name's phone hasn't reported yet; it applies as soon as the phone is online.",
            KGColors.warning
        )
        else -> Hero("Starting on $name's phone", "The setting is saved. It applies as soon as the phone is online.", KGColors.warning)
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (filter?.hadLoadErrors == true) {
            KGCard(Modifier.fillMaxWidth()) {
                Text(
                    "Some blocklists failed to load on this phone, so \"Check a site\" and site counts may be incomplete. Try restarting the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.danger
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // State hero: what the child's phone reports, signal colour as a glyph, never as a fill.
        KGCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(hero.color.copy(alpha = 0.12f), KGShapes.circle),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(18.dp).background(hero.color, KGShapes.circle))
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(hero.headline, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        hero.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KGColors.inkMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (wanted) {
                KGSecondaryButton("Turn off", onClick = { editor.setEnabled(false) }, enabled = remote.loaded && !saving)
            } else {
                KGPrimaryButton("Turn on web protection", onClick = { editor.setEnabled(true) }, enabled = remote.loaded && !saving)
            }
            if (saving) {
                Text("Saving…", style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted, modifier = Modifier.padding(top = 10.dp))
            }
            Text(
                device.updatedAtMillis?.let {
                    "Last report from $name's phone: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                } ?: "$name's phone hasn't reported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (wanted && device.running && child.age != AgeProfile.ADULTS_18_PLUS) {
                Text(
                    "Safe search and YouTube Restricted Mode are on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        KGSectionHeader("Check a site", hint = "See what K-Guard would do for $name, and why.")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KGTextField(
                site,
                { site = it; checked = null },
                "example.com",
                Modifier.weight(1f),
                keyboardType = KeyboardType.Uri
            )
            KGSecondaryButton(
                "Check",
                enabled = site.isNotBlank() && filter != null,
                onClick = {
                    val f = filter ?: return@KGSecondaryButton
                    val s = remote.settings
                    val policy = VpnPolicy(child.age, child.categoryRules, s.customBlock, s.customAllow, s.educationalAllow, s.aiFilter)
                    val domain = site.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
                    val v = f.evaluate(domain, policy)
                    siteBlocked = v.blocked
                    checked = domain to DomainFilter.explain(v, policy)
                }
            )
        }
        checked?.let { (domain, why) ->
            KGCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KGStatusTag(
                        if (siteBlocked) "Blocked" else "Allowed",
                        if (siteBlocked) KGColors.danger else KGColors.success
                    )
                    Text(domain, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (siteBlocked) KGColors.danger else KGColors.success,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
        KGSectionHeader("How it works")
        Text(
            "Your settings are saved to your family account and applied on $name's phone within seconds while it is online. " +
                "That phone sets up a private connection that checks website names only, so harmful sites are blocked in every browser and app. " +
                "K-Guard cannot see pages, messages or photos. Use the Text and Image checkers for content.",
            style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted
        )
        Spacer(Modifier.height(8.dp))
        KGSectionHeader("Good to know")
        Text(
            "• When the phone is offline it keeps enforcing the last settings it received.\n" +
                "• It may not stop apps or browsers that use their own encrypted DNS (for example \"Secure DNS\" in Chrome). Turn that off on $name's phone for best results.\n" +
                "• If protection is switched off in Android's settings on $name's phone, K-Guard turns it back on and you get a notification.\n" +
                "• The built-in site list is a starting set. Add your own blocks and allows under Categories.",
            style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted
        )
    }
}

// ---------------------------------------------------------------- Categories: age-aware protection
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesTab(session: SessionViewModel, c: ChildProfile, editor: ParentWebEditor, filter: DomainFilter?) {
    val customBlock by editor.customBlock.collectAsState()
    val customAllow by editor.customAllow.collectAsState()
    val edu by editor.educationalAllow.collectAsState()
    val ai by editor.aiFilter.collectAsState()
    val busy by session.busy.collectAsState()
    var blockInput by remember(c.id) { mutableStateOf("") }
    var allowInput by remember(c.id) { mutableStateOf("") }
    var blockErr by remember(c.id) { mutableStateOf(false) }
    var allowErr by remember(c.id) { mutableStateOf(false) }

    val policy = VpnPolicy(c.age, c.categoryRules)

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("${c.name}: ${c.age.title} (${c.age.ageRange})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(c.age.description, style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted)
        Spacer(Modifier.height(4.dp))
        Text(
            "\"Default\" follows the age group. Change a category to Block or Allow for ${c.name}. Sexual content can't be allowed under 18.",
            style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted
        )
        Spacer(Modifier.height(8.dp))

        SafetyCategory.entries.forEach { cat ->
            val eff = DomainFilter.effective(cat, policy)
            val current = CategoryRule.fromId(c.categoryRules[cat.id])
            val shown = when (current) {
                CategoryRule.BLOCK -> "Block"
                CategoryRule.ALLOW -> if (eff.source == Source.LOCKED) "Block" else "Allow"
                CategoryRule.WARN -> "Warn"
                CategoryRule.BLUR -> "Blur"
                null -> "Default"
            }
            val count = filter?.listSize(cat) ?: 0
            KGCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cat.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$count site(s) listed" + when (eff.source) {
                                Source.AGE -> " · age default"; Source.RULE -> " · parent rule"; Source.LOCKED -> " · locked under 18"
                            },
                            style = MaterialTheme.typography.labelSmall, color = KGColors.inkMuted
                        )
                    }
                    Text(
                        if (eff.blocked) "Blocked" else "Allowed",
                        color = if (eff.blocked) KGColors.danger else KGColors.success,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = shown == "Default", enabled = !busy, onClick = { session.saveChild(c.copy(categoryRules = c.categoryRules - cat.id)) }, label = { Text("Default") })
                    FilterChip(selected = shown == "Block", enabled = !busy, onClick = { session.saveChild(c.copy(categoryRules = c.categoryRules + (cat.id to CategoryRule.BLOCK.id))) }, label = { Text("Block") })
                    if (!cat.lockedAgainstRelaxing || c.age == AgeProfile.ADULTS_18_PLUS) {
                        FilterChip(selected = shown == "Warn", enabled = !busy, onClick = { session.saveChild(c.copy(categoryRules = c.categoryRules + (cat.id to CategoryRule.WARN.id))) }, label = { Text("Warn") })
                        FilterChip(selected = shown == "Blur", enabled = !busy, onClick = { session.saveChild(c.copy(categoryRules = c.categoryRules + (cat.id to CategoryRule.BLUR.id))) }, label = { Text("Blur") })
                        FilterChip(selected = shown == "Allow", enabled = !busy, onClick = { session.saveChild(c.copy(categoryRules = c.categoryRules + (cat.id to CategoryRule.ALLOW.id))) }, label = { Text("Allow") })
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

        KGToggleRow(
            title = "Educational sites",
            description = "Always allow recognised learning and health-education sites (such as encyclopedias and bullying-help pages), even if a category would block them.",
            checked = edu,
            onCheckedChange = { editor.setEducationalAllow(it) }
        )

        Spacer(Modifier.height(12.dp))

        KGToggleRow(
            title = "AI website filter",
            description = "When ${c.name} opens a site that is on no list, K-Guard judges it on ${c.name}'s phone — offline — and blocks it if it looks unsuitable for the ${c.age.title} profile. Parent allow/block lists always win.",
            checked = ai,
            onCheckedChange = { editor.setAiFilter(it) }
        )

        Spacer(Modifier.height(12.dp))
        DomainEditor("Always allow", "A parent always allows these, whatever the category.", allowInput, { allowInput = it; allowErr = false }, allowErr, customAllow, onAdd = {
            allowErr = !editor.addCustomAllow(allowInput); if (!allowErr) allowInput = ""
        }, onRemove = { editor.removeCustomAllow(it) })
        Spacer(Modifier.height(12.dp))
        DomainEditor("Always block", "Blocked for ${c.name} on top of the categories above.", blockInput, { blockInput = it; blockErr = false }, blockErr, customBlock, onAdd = {
            blockErr = !editor.addCustomBlock(blockInput); if (!blockErr) blockInput = ""
        }, onRemove = { editor.removeCustomBlock(it) })
        Spacer(Modifier.height(16.dp))
        Text("Changes reach ${c.name}'s phone within seconds while it is online.", style = MaterialTheme.typography.labelSmall, color = KGColors.inkMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DomainEditor(
    title: String, hint: String, value: String, onChange: (String) -> Unit, error: Boolean,
    items: Set<String>, onAdd: () -> Unit, onRemove: (String) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        KGTextField(
            value,
            onChange,
            "example.com",
            Modifier.weight(1f),
            isError = error,
            supportingText = if (error) "Enter a website like example.com" else null,
            keyboardType = KeyboardType.Uri
        )
        KGSecondaryButton("Add", onClick = onAdd)
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.sorted().forEach { d ->
            InputChip(
                selected = false,
                onClick = { onRemove(d) },
                label = { Text(d) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove $d",
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

// ---------------------------------------------------------------- Activity: alerts, explanations, review
@Composable
private fun ActivityTab(session: SessionViewModel, editor: ParentWebEditor, webEvents: List<SafetyEvent>) {
    var onlyOpen by remember { mutableStateOf(true) }
    val shown = webEvents.filter { !onlyOpen || it.isOpen }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        KGSectionHeader(
            "Alerts for parents",
            hint = "Each blocked site creates an alert (one per site every 10 minutes). Notifications follow your Privacy & Notifications settings."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilterChip(selected = onlyOpen, onClick = { onlyOpen = true }, label = { Text("Needs review") })
            FilterChip(selected = !onlyOpen, onClick = { onlyOpen = false }, label = { Text("All") })
        }
        if (shown.isEmpty()) {
            Text(
                if (onlyOpen) "No web alerts need review." else "No web alerts yet.",
                color = KGColors.inkMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        shown.forEach { ev -> WebAlertCard(ev, session, editor) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebAlertCard(ev: SafetyEvent, session: SessionViewModel, editor: ParentWebEditor) {
    var pendingAllow by remember { mutableStateOf<String?>(null) }
    KGCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ev.childName.ifBlank { "Child" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                KGStatusTag(
                    ev.category.replace('_', ' '),
                    KGColors.accent,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            DecisionBadge(ev.parsedDecision)
        }
        Text(ev.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        ev.snippet?.let {
            Text("Site: $it", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        }
        ev.createdAt?.let {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.toDate()),
                style = MaterialTheme.typography.labelSmall,
                color = KGColors.inkMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (ev.isOpen) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KGSecondaryButton("Keep blocked", onClick = { session.review(ev.id, ParentAction.KEEP_BLOCKED, "") })
                if (ev.snippet != null) {
                    KGDangerButton("Allow site", onClick = { pendingAllow = ev.snippet })
                }
                KGTextLink("Dismiss", onClick = { session.review(ev.id, ParentAction.DISMISS, "") })
            }
            if (ev.snippet == null) {
                Text(
                    "The site name isn't stored. Turn on excerpts in Privacy settings to allow sites from here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KGColors.inkMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Text(
                "Reviewed: " + (ParentAction.entries.find { it.id == ev.parentAction }?.label ?: "done"),
                color = KGColors.success,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    pendingAllow?.let { domain ->
        AllowSiteDialog(
            domain,
            onConfirm = { editor.addCustomAllow(domain); session.review(ev.id, ParentAction.ALLOW, "Site allowed"); pendingAllow = null },
            onDismiss = { pendingAllow = null }
        )
    }
}

/** Confirms before a one-tap "allow" permanently lets a site through for this child. */
@Composable
private fun AllowSiteDialog(domain: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = KGShapes.card,
        title = { Text("Always allow $domain?") },
        text = { Text("This site will be allowed in every browser and app on the child's phone, whatever the category rules say. You can remove it again under Categories.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow", color = KGColors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = KGColors.inkMuted) } }
    )
}
