package com.kguard.app.ui.components

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kguard.app.R
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ModerationDecision
import com.kguard.app.data.model.ModerationResponse
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes

/**
 * Requests a runtime permission and reports the outcome via [onResult] (true = granted, false = denied).
 * Returns the zero-arg action to invoke from a click handler.
 * [onResult] defaults to a no-op so existing call sites that ignore the result keep compiling.
 */
@Composable
fun RequestPermission(
    permission: String,
    onResult: (Boolean) -> Unit = {}
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onResult(granted)
    }
    return { launcher.launch(permission) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KGuardTopAppBar(
    title: String,
    canNavigateBack: Boolean = false,
    onNavigateBack: () -> Unit = {},
    /** Non-null turns the title into a search field (see [onSearchQueryChange], [onSearchClose]). */
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchClose: () -> Unit = {},
    searchPlaceholder: String = "Search",
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = {
            if (searchQuery != null) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(searchPlaceholder) },
                    singleLine = true,
                    shape = KGShapes.input,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        navigationIcon = {
            if (searchQuery != null) {
                IconButton(onClick = onSearchClose) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                }
            } else if (canNavigateBack) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate Back"
                    )
                }
            }
        },
        actions = { if (searchQuery == null) actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

private data class DecisionStyle(
    val color: Color,
    val label: String,
    val icon: ImageVector
)

@Composable
fun DecisionBadge(decision: ModerationDecision) {
    val style = when (decision) {
        ModerationDecision.ALLOW -> DecisionStyle(KGColors.success, "ALLOW", Icons.Default.CheckCircle)
        ModerationDecision.WARN -> DecisionStyle(KGColors.warning, "WARN", Icons.Default.Warning)
        ModerationDecision.BLUR -> DecisionStyle(KGColors.accent, "BLUR", Icons.Default.Info)
        ModerationDecision.BLOCK -> DecisionStyle(KGColors.danger, "BLOCK", Icons.Default.Lock)
        ModerationDecision.REVIEW -> DecisionStyle(KGColors.inkMuted, "REVIEW", Icons.Default.Info)
    }

    // Colour as signal: faint tinted pill, never a filled block.
    Row(
        modifier = Modifier
            .clip(KGShapes.pill)
            .background(style.color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = style.label,
            tint = style.color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = style.label,
            color = style.color,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun ModerationResultCard(
    response: ModerationResponse,
    activeAgeProfile: AgeProfile,
    originalContent: String? = null,
    isImage: Boolean = false
) {
    var isBlurRevealed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = KGShapes.card
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Category: ${response.category}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Evaluated for ${activeAgeProfile.title} (${activeAgeProfile.ageRange})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DecisionBadge(decision = response.parsedDecision)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (response.parsedDecision == ModerationDecision.REVIEW) {
                    // Nothing was scored, so don't show a risk number or a suitability verdict.
                    Text(
                        text = "Not assessed yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KGColors.inkMuted,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "Risk Score: ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(response.riskScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (response.riskScore > 0.6f) KGColors.danger else KGColors.success,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (response.ageAppropriate) "Suitable for Age" else "Unsuitable for Age",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (response.ageAppropriate) KGColors.success else KGColors.danger,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Explanation: ${response.reason}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (response.parsedDecision) {
                ModerationDecision.ALLOW -> {
                    if (!isImage && originalContent != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(KGColors.success.copy(alpha = 0.10f))
                                .padding(12.dp)
                        ) {
                            Text(text = originalContent, color = KGColors.success)
                        }
                    }
                }
                ModerationDecision.WARN -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(KGColors.warning.copy(alpha = 0.10f))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = KGColors.warning)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Safety Warning",
                                fontWeight = FontWeight.Bold,
                                color = KGColors.warning
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This content contains sensitive terms. Please exercise discretion.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KGColors.danger
                        )
                        if (!isImage && originalContent != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = originalContent, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                ModerationDecision.BLUR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(KGColors.accent.copy(alpha = 0.10f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Content Blurred for Safety",
                            fontWeight = FontWeight.Bold,
                            color = KGColors.accent
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (isImage && originalContent != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = Uri.parse(originalContent),
                                    contentDescription = "Blurred image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .blur(if (isBlurRevealed) 0.dp else 25.dp)
                                )
                                if (!isBlurRevealed && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                                    // Modifier.blur is a no-op before Android 12, so hide the image completely instead.
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp)
                                            .background(Color.Black.copy(alpha = 0.6f))
                                    )
                                }
                                if (!isBlurRevealed && response.canReveal) {
                                    Button(
                                        onClick = { isBlurRevealed = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                                    ) {
                                        Text("Tap to Reveal Preview")
                                    }
                                }
                            }
                        } else if (originalContent != null) {
                            Text(
                                text = if (isBlurRevealed) originalContent else "••••••••••••••••••••••••••••••••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            if (response.canReveal) {
                                Button(
                                    onClick = { isBlurRevealed = !isBlurRevealed },
                                    modifier = Modifier.padding(top = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                                ) {
                                    Text(if (isBlurRevealed) "Hide Content" else "Reveal Content")
                                }
                            } else {
                                Text(
                                    text = "A parent has set this profile so blurred items stay hidden.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KGColors.inkMuted
                                )
                            }
                        }
                    }
                }
                ModerationDecision.BLOCK -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(KGColors.danger.copy(alpha = 0.10f))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Blocked",
                            tint = KGColors.danger,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Content Restricted",
                            fontWeight = FontWeight.Bold,
                            color = KGColors.danger
                        )
                        Text(
                            text = "This item cannot be viewed under ${activeAgeProfile.title} settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KGColors.danger.copy(alpha = 0.7f)
                        )
                    }
                }
                ModerationDecision.REVIEW -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "This item is held until a parent reviews it. It has not been approved.",
                            color = KGColors.inkMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgeProfileCard(
    profile: AgeProfile,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onSelect() }
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) KGColors.accent else Color.Transparent,
                shape = KGShapes.cardSmall
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = KGShapes.cardSmall
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = profile.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${profile.ageRange})",
                        style = MaterialTheme.typography.labelSmall,
                        color = KGColors.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The K-Guard brand mark: the launcher padlock artwork centred on a white disc.
 * Use [modifier] to size it, e.g. `KGuardLogo(Modifier.size(120.dp))`.
 */
@Composable
fun KGuardLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "K-Guard logo",
            modifier = Modifier.fillMaxSize()
        )
    }
}
