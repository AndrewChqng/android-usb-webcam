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
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
                // Section 1: Presets
                Text(
                    text = "Preset Profiles",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (opt in UvcResolutionProfileOption.values) {
                        val label = when (opt) {
                            UvcResolutionProfileOption.Max1080p -> "Max 1080p"
                            UvcResolutionProfileOption.Max1440p -> "Max 1440p"
                            UvcResolutionProfileOption.Max4K -> "Max 4K"
                            UvcResolutionProfileOption.Max720p -> "Max 720p"
                            UvcResolutionProfileOption.Portrait916 -> "9:16 Vertical"
                            UvcResolutionProfileOption.Only1080p -> "Forced 1080p"
                            UvcResolutionProfileOption.Only720p -> "Forced 720p"
                            UvcResolutionProfileOption.Only4K -> "Forced 4K"
                            UvcResolutionProfileOption.Custom -> "Custom"
                        }
                        FilterChip(
                            selected = currentProfile == opt,
                            onClick = { onSelectProfile(opt) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
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
                            text = "Sensor Resolutions (${filteredItems.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { showCustomInput = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Custom", style = MaterialTheme.typography.labelSmall)
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
