package com.kguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes

/**
 * The K-Guard UI kit (DESIGN.md). Every screen composes from these; screens never style their
 * own buttons, fields or cards. Structure per the Framer system: pill CTAs, filled inputs with
 * a single accent focus ring, surface-lift cards, and semantic colour as text — never as fills.
 */

// ---------------------------------------------------------------- Buttons

/** Primary CTA: black pill, white text, minimum 48dp touch height. */
@Composable
fun KGPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = KGShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = KGColors.inkMuted
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary action: light-gray pill, ink text. */
@Composable
fun KGSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = KGShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = KGColors.inkMuted
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Destructive or opt-out action: neutral pill, danger text — colour as signal, never as fill. */
@Composable
fun KGDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = KGShapes.pill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = KGColors.danger
        ),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Tertiary/inline action: text-only, accent colour (the system's "hyperlink" voice). */
@Composable
fun KGTextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = KGShapes.pill,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = KGColors.accent),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- Inputs

/**
 * Filled field per DESIGN.md: surface fill, 10dp radius, hairline border at rest,
 * the single accent-blue ring on focus.
 */
@Composable
fun KGTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = singleLine,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = KGShapes.input,
            leadingIcon = leading,
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = KGColors.accent,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = KGColors.inkMuted
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) KGColors.danger else KGColors.inkMuted,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- Surfaces

/** Standard content card: surface lift, 20dp radius, optional click with ripple. */
@Composable
fun KGCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = KGShapes.card
    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = modifier
        ) { Column(Modifier.padding(16.dp), content = content) }
    } else {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = modifier
        ) { Column(Modifier.padding(16.dp), content = content) }
    }
}

/** Section title with optional muted guidance line. */
@Composable
fun KGSectionHeader(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier = modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Small status chip: faint tinted background, signal-coloured text. */
@Composable
fun KGStatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), KGShapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Settings-style row: title, description, trailing Switch. */
@Composable
fun KGToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = KGColors.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

/** Honest empty state for screens whose data genuinely isn't there yet. */
@Composable
fun KGEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow, KGShapes.circle)
                .padding(18.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = KGColors.inkMuted,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = KGColors.inkMuted,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (action != null) Row { action() }
    }
}
