package com.andrewchqng.usbwebcam.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andrewchqng.usbwebcam.settings.UserPreferences
import com.andrewchqng.usbwebcam.settings.UvcResolutionProfileOption
import kotlin.math.roundToInt

@Composable
fun ResolutionProfileCard(
    userPreferences: UserPreferences,
    isUvcLoopRunning: Boolean,
    onSelectProfile: (UvcResolutionProfileOption) -> Unit,
    onOpenCustomDialog: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val currentProfile = userPreferences.uvcResolutionProfileOption
        val tiers = UvcResolutionProfileOption.maxTiers
        val tierIdx = tiers.indexOf(currentProfile).let {
            if (it >= 0) it else tiers.indexOf(UvcResolutionProfileOption.Max1080p).coerceAtLeast(0)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (currentProfile == UvcResolutionProfileOption.Custom) {
                            "Exposed Resolutions (Custom Set)"
                        } else {
                            "Max Resolution: ${currentProfile.shortLabel}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${userPreferences.selectedResolutions.size} active formats • Default: ${userPreferences.defaultResolution}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onOpenCustomDialog,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Custom", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (isUvcLoopRunning) {
                Text(
                    text = "(Restart webcam to apply profile changes)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Slider(
                value = tierIdx.toFloat(),
                onValueChange = { value ->
                    val snapped = value.roundToInt().coerceIn(0, tiers.lastIndex)
                    onSelectProfile(tiers[snapped])
                },
                valueRange = 0f..tiers.lastIndex.toFloat(),
                steps = tiers.size - 2
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (t in tiers) {
                    Text(
                        text = if (t == UvcResolutionProfileOption.Max4K) "4K" else t.shortLabel.removeSuffix("p"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (currentProfile == t) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentProfile == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
