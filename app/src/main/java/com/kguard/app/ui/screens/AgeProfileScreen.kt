package com.kguard.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.ui.components.AgeProfileCard
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.AgeProfileViewModel

@Composable
fun AgeProfileScreen(
    viewModel: AgeProfileViewModel,
    onSaveAndContinue: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val savedProfile by viewModel.selectedAgeProfile.collectAsState()
    val pendingSelection by viewModel.pendingSelection.collectAsState()
    val profileLoaded by viewModel.profileLoaded.collectAsState()
    // The picker stages a pending selection; the saved value is only claimed once it is persisted.
    val displayedProfile = pendingSelection ?: savedProfile

    AgeProfileContent(
        selectedProfile = displayedProfile,
        profileLoaded = profileLoaded,
        onSelectProfile = { viewModel.selectAgeProfile(it) },
        onSaveAndContinue = {
            viewModel.confirmSelection()
            onSaveAndContinue()
        },
        onNavigateBack = {
            // Leaving without confirming cancels the staged selection.
            viewModel.clearSelection()
            onNavigateBack()
        }
    )
}

@Composable
fun AgeProfileContent(
    selectedProfile: AgeProfile,
    profileLoaded: Boolean = true,
    onSelectProfile: (AgeProfile) -> Unit,
    onSaveAndContinue: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = "Select Age Profile",
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
        ) {
            Text(
                text = "Target Age Suitability",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose an age group to configure content moderation thresholds and decision interventions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!profileLoaded) {
                // Wait for the persisted profile before showing the list, so the highlight never
                // briefly claims the DEFAULT profile on cold start.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(AgeProfile.entries) { profile ->
                        AgeProfileCard(
                            profile = profile,
                            isSelected = profile == selectedProfile,
                            onSelect = { onSelectProfile(profile) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            KGPrimaryButton(
                text = "Confirm & Save Profile",
                onClick = onSaveAndContinue,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AgeProfileScreenPreview() {
    KGuardTheme {
        AgeProfileContent(
            selectedProfile = AgeProfile.PRETEENS_8_12,
            onSelectProfile = {},
            onSaveAndContinue = {},
            onNavigateBack = {}
        )
    }
}
