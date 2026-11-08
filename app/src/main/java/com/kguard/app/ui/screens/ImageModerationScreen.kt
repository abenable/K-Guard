package com.kguard.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ModerationUiState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.components.ModerationResultCard
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.ImageModerationViewModel

@Composable
fun ImageModerationScreen(
    viewModel: ImageModerationViewModel,
    activeAgeProfile: AgeProfile,
    activeChildId: String?,
    profileLoaded: Boolean = true,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val selectedImageUri by viewModel.selectedImageUri.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setSelectedImage(uri)
    }

    ImageModerationContent(
        selectedImageUri = selectedImageUri,
        uiState = uiState,
        activeAgeProfile = activeAgeProfile,
        profileLoaded = profileLoaded,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onSubmit = { viewModel.submitImageForModeration(context, activeChildId) },
        onReset = { viewModel.resetState() },
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun ImageModerationContent(
    selectedImageUri: Uri?,
    uiState: ModerationUiState,
    activeAgeProfile: AgeProfile,
    profileLoaded: Boolean = true,
    onPickImage: () -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val isLoading = uiState is ModerationUiState.Loading

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = "Image Content Moderation",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            KGSectionHeader("Select Image for Safety Moderation")
            if (profileLoaded) {
                Text(
                    text = "Evaluating under profile: ${activeAgeProfile.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preview: the chosen image, or a quiet placeholder until one is picked.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(KGShapes.cardSmall)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, KGColors.hairline, KGShapes.cardSmall),
                contentAlignment = Alignment.Center
            ) {
                if (selectedImageUri != null) {
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = "Selected Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = KGColors.inkMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No image selected yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            KGSecondaryButton(
                text = if (selectedImageUri == null) "Pick image from gallery" else "Choose a different image",
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            KGPrimaryButton(
                text = if (isLoading) "Scanning…" else "Moderate Image Safety",
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && selectedImageUri != null,
                leading = if (isLoading) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                } else null
            )

            Spacer(modifier = Modifier.height(20.dp))

            when (val state = uiState) {
                is ModerationUiState.Idle -> {
                    Text(
                        text = "Pick an image and tap 'Moderate Image Safety' to run AI moderation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is ModerationUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ModerationUiState.Success -> {
                    Text(
                        text = "Moderation Result",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ModerationResultCard(
                        response = state.response,
                        activeAgeProfile = activeAgeProfile,
                        originalContent = state.originalContent,
                        isImage = true
                    )
                    KGTextLink(
                        text = "Pick Another Image",
                        onClick = onReset,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
                is ModerationUiState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        color = KGColors.danger,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ImageModerationScreenPreview() {
    KGuardTheme {
        ImageModerationContent(
            selectedImageUri = null,
            uiState = ModerationUiState.Idle,
            activeAgeProfile = AgeProfile.PRETEENS_8_12,
            onPickImage = {},
            onSubmit = {},
            onReset = {},
            onNavigateBack = {}
        )
    }
}
