package com.kguard.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGuardLogo
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme

/**
 * Welcome/marketing moment (DESIGN.md). Monochrome light canvas with one gradient spotlight
 * card as the hero rhythm break; the single chromatic accent stays reserved for signals.
 */
@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 48.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                KGuardLogo(modifier = Modifier.size(96.dp))

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "K-Guard",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Your child's safety, on every app they touch",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Moderation, alerts and screen-time controls in one place — tuned to your child's age.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(40.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    FeatureRow(
                        icon = Icons.Default.ChildCare,
                        title = "Age-aware moderation",
                        description = "Tailored safety evaluations based on customizable age profiles (Kids, Pre-Teens, Teens, Adults)."
                    )
                    FeatureRow(
                        icon = Icons.Default.NotificationsActive,
                        title = "Smart interventions",
                        description = "Automatic warnings, content blurring, and access blocks designed to safeguard young minds."
                    )
                    FeatureRow(
                        icon = Icons.Default.ImageSearch,
                        title = "Text & image analysis",
                        description = "Real-time AI-assisted evaluation for submitted text and image content."
                    )
                    FeatureRow(
                        icon = Icons.Default.Apps,
                        title = "Apps, web & screen time",
                        description = "Set daily limits and manage which apps and sites your child can use."
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                HowItWorksSpotlight()

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Opaque canvas behind the pinned CTA: content scrolls underneath a clean edge
            // instead of visibly poking out around the pill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                KGPrimaryButton(
                    text = "Get started",
                    onClick = onGetStarted,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Icon in a neutral surface circle, semibold title, muted description. Monochrome by design. */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, KGShapes.circle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * The screen's single chromatic block: the violet-magenta gradient spotlight (DESIGN.md
 * signature) holding the three-step product summary, all in white on the gradient.
 */
@Composable
private fun HowItWorksSpotlight() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(listOf(KGColors.gradientViolet, KGColors.gradientMagenta)),
                shape = KGShapes.spotlight
            )
            .padding(24.dp)
    ) {
        Text(
            text = "How it works",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(20.dp))

        SpotlightStep(
            number = "1",
            title = "Pair your child's device",
            description = "Sign in as a parent, then link their phone with a one-time code."
        )
        Spacer(modifier = Modifier.height(16.dp))
        SpotlightStep(
            number = "2",
            title = "K-Guard filters in real time",
            description = "Every text and image is checked before your child sees it."
        )
        Spacer(modifier = Modifier.height(16.dp))
        SpotlightStep(
            number = "3",
            title = "You stay in the loop",
            description = "Get alerts and keep control of apps, web and screen time."
        )
    }
}

@Composable
private fun SpotlightStep(
    number: String,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color.White.copy(alpha = 0.2f), KGShapes.circle),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun OnboardingScreenPreview() {
    KGuardTheme {
        OnboardingScreen(onGetStarted = {})
    }
}
