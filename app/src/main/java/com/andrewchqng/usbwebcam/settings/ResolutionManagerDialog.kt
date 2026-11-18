package com.andrewchqng.usbwebcam.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResolutionManagerDialog(
    availableResolutions: List<ResolutionItem>,
    selectedResolutions: Set<String>,
    defaultResolution: String,
    customResolutions: Set<String>,
    currentProfile: UvcResolutionProfileOption,
    onSelectProfile: (UvcResolutionProfileOption) -> Unit,
    onToggleResolution: (String, Boolean) -> Unit,
    onSetDefaultResolution: (String) -> Unit,
    onAddCustomResolution: (Int, Int) -> Boolean,
    onRemoveCustomResolution: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onDeselectAll: (List<String>) -> Unit,
    onDismissRequest: () -> Unit
) {
    var selectedAspectFilter by remember { mutableStateOf("All") }
    var showCustomInput by remember { mutableStateOf(false) }
    var customWidthStr by remember { mutableStateOf("") }
    var customHeightStr by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    val aspectFilters = listOf("All", "16:9", "9:16", "4:3", "1:1", "Other")

    // Filter available items according to aspect ratio
    val filteredItems = remember(availableResolutions, selectedAspectFilter) {
        if (selectedAspectFilter == "All") {
            availableResolutions
        } else {
            availableResolutions.filter { item ->
                when (selectedAspectFilter) {
                    "16:9" -> item.aspectRatio == "16:9"
                    "9:16" -> item.aspectRatio == "9:16"
                    "4:3" -> item.aspectRatio == "4:3" || item.aspectRatio == "3:4"
                    "1:1" -> item.aspectRatio == "1:1"
                    "Other" -> item.aspectRatio !in listOf("16:9", "9:16", "4:3", "3:4", "1:1")
                    else -> true
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(
                    text = "Resolution Profiles & Set",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${selectedResolutions.size} active (Max 16). Default: $defaultResolution",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                // Section 1: Discrete Max Resolution Slider
                val tiers = UvcResolutionProfileOption.maxTiers
                val tierIdx = tiers.indexOf(currentProfile).let { if (it >= 0) it else 5 } // Default visual position 1080p when Custom
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (currentProfile == UvcResolutionProfileOption.Custom) {
                            "Max Resolution: Custom Set"
                        } else {
                            "Max Resolution: ${currentProfile.shortLabel}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "144p – 2160p",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Section 2: Aspect Ratio filter chips
                Text(
                    text = "Filter by Aspect Ratio",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (aspect in aspectFilters) {
                        FilterChip(
                            selected = selectedAspectFilter == aspect,
                            onClick = { selectedAspectFilter = aspect },
                            label = { Text(aspect, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Section 3: Add Custom Resolution Row
                if (showCustomInput) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Add Custom Resolution",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customWidthStr,
                                    onValueChange = { customWidthStr = it },
                                    label = { Text("Width") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Text("×", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = customHeightStr,
                                    onValueChange = { customHeightStr = it },
                                    label = { Text("Height") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        val w = customWidthStr.toIntOrNull()
                                        val h = customHeightStr.toIntOrNull()
                                        if (w != null && h != null && w > 0 && h > 0) {
                                            if (onAddCustomResolution(w, h)) {
                                                customWidthStr = ""
                                                customHeightStr = ""
                                                showCustomInput = false
                                                customError = null
                                            } else {
                                                customError = "Resolution outside limits"
                                            }
                                        } else {
                                            customError = "Invalid width/height"
                                        }
                                    }
                                ) {
                                    Text("Add")
                                }
                            }
                            if (customError != null) {
                                Text(
                                    text = customError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Resolutions (${filteredItems.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onSelectAll(filteredItems.map { it.key }) }
                            ) {
                                Text("Select All", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { onDeselectAll(filteredItems.map { it.key }) }
                            ) {
                                Text("Deselect All", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { showCustomInput = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Add", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Section 4: Resolution Checklist
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(filteredItems, key = { it.key }) { item ->
                        val isChecked = selectedResolutions.contains(item.key)
                        val isDefault = defaultResolution == item.key
                        val isCustomItem = customResolutions.contains(item.key)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToggleResolution(item.key, !isChecked)
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    onToggleResolution(item.key, checked)
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${item.width}×${item.height}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.aspectRatio,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (isDefault) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Default",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = item.displayName.substringBefore(" ("),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Star icon to set as default startup resolution
                            IconButton(
                                onClick = { onSetDefaultResolution(item.key) }
                            ) {
                                Icon(
                                    imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                    contentDescription = "Set as default",
                                    tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Delete button if custom resolution
                            if (isCustomItem) {
                                IconButton(
                                    onClick = { onRemoveCustomResolution(item.key) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete custom resolution",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Done")
            }
        }
    )
}
