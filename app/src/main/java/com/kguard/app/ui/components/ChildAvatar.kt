package com.kguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes

/** A child's photo (uploaded, so it shows on every phone in the family), or their initial. */
@Composable
fun ChildAvatar(name: String, photoUrl: String, size: Dp, modifier: Modifier = Modifier) {
    if (photoUrl.isNotBlank()) {
        AsyncImage(
            model = photoUrl,
            contentDescription = null,
            modifier = modifier.size(size).clip(KGShapes.circle),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(KGShapes.circle).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "•",
                style = if (size >= 96.dp) MaterialTheme.typography.headlineMedium
                else if (size >= 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                color = KGColors.inkMuted
            )
        }
    }
}

@Composable
fun ChildAvatar(child: ChildProfile, size: Dp, modifier: Modifier = Modifier) =
    ChildAvatar(child.name, child.photoUrl, size, modifier)
