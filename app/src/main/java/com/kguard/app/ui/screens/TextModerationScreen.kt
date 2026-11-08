package com.kguard.app.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ModerationUiState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.components.ModerationResultCard
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.TextModerationViewModel

@Composable
fun TextModerationScreen(
    viewModel: TextModerationViewModel,
    activeAgeProfile: AgeProfile,
    activeChildId: String?,
    profileLoaded: Boolean = true,
    onNavigateBack: () -> Unit
) {
    val inputText by viewModel.inputText.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    TextModerationContent(
        inputText = inputText,
        uiState = uiState,
        activeAgeProfile = activeAgeProfile,
        profileLoaded = profileLoaded,
        onInputTextChange = { viewModel.updateInputText(it) },
        onSubmit = { viewModel.submitTextForModeration(activeChildId) },
        onReset = { viewModel.resetState() },
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun TextModerationContent(
    inputText: String,
    uiState: ModerationUiState,
    activeAgeProfile: AgeProfile,
    profileLoaded: Boolean = true,
    onInputTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val isLoading = uiState is ModerationUiState.Loading

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = "Text Content Moderation",
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
            KGSectionHeader("Enter Text for Safety Analysis")
            if (profileLoaded) {
                Text(
                    text = "Evaluating against active profile: ${activeAgeProfile.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            KGTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                label = "Input message or comment",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )

            Spacer(modifier = Modifier.height(12.dp))

            KGPrimaryButton(
                text = if (isLoading) "Analyzing…" else "Evaluate Content Safety",
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && inputText.isNotBlank(),
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
                        text = "Submit text above to view safety classification and decision interventions.",
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
                        isImage = false
                    )
                    KGTextLink(
                        text = "Clear & Evaluate New Text",
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
fun TextModerationScreenPreview() {
    KGuardTheme {
        TextModerationContent(
            inputText = "You are ugly and nobody likes you",
            uiState = ModerationUiState.Idle,
            activeAgeProfile = AgeProfile.PRETEENS_8_12,
            onInputTextChange = {},
            onSubmit = {},
            onReset = {},
            onNavigateBack = {}
        )
    }
}
