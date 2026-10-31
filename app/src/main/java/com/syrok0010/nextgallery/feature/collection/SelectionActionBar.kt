package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R

@Composable
internal fun BoxScope.SelectionActionBar(
    selectedCount: Int,
    selectionLocked: Boolean,
    onCancel: () -> Unit,
    actions: List<SelectionAction>,
    onPerform: (SelectionAction) -> Unit,
) {
    val placement = placeSelectionActions(actions)
    var menuExpanded by remember { mutableStateOf(false) }
    val enabled = selectedCount > 0 && !selectionLocked
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onCancel, enabled = !selectionLocked) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.selection_cancel),
                )
            }
            val selectedLabel = stringResource(R.string.selection_count, selectedCount)
            Text(
                "$selectedCount",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = selectedLabel },
            )
            Spacer(Modifier.width(4.dp))
            placement.buttons.forEach { action ->
                when {
                    action.isPrimary -> Button(onClick = { onPerform(action) }, enabled = enabled) {
                        Icon(action.icon, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(action.label, maxLines = 1)
                    }

                    action.isDestructive -> IconButton(
                        onClick = { onPerform(action) },
                        enabled = enabled,
                    ) { Icon(action.icon, contentDescription = action.label) }

                    else -> FilledTonalIconButton(
                        onClick = { onPerform(action) },
                        enabled = enabled,
                    ) { Icon(action.icon, contentDescription = action.label) }
                }
            }
            if (placement.overflow.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                        Icon(
                            painterResource(R.drawable.ic_more),
                            contentDescription = stringResource(R.string.selection_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded && enabled,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        placement.overflow.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label, maxLines = 1) },
                                leadingIcon = { Icon(action.icon, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onPerform(action)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
