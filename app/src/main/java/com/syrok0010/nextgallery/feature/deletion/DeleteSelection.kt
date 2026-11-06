package com.syrok0010.nextgallery.feature.deletion

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import kotlinx.serialization.json.Json

/**
 * Sends phone copies to the system trash first: its dialog is the user's final confirmation, so
 * the cloud half starts only after RESULT_OK. A cancelled dialog deletes nothing anywhere.
 */
@Composable
internal fun rememberDeleteAction(
    onConfirmed: (DeletionTargets) -> Unit,
    onStarted: () -> Unit,
): (DeletionTargets) -> Unit {
    val context = LocalContext.current
    val confirm by rememberUpdatedState(onConfirmed)
    val started by rememberUpdatedState(onStarted)
    // Survives activity recreation while the system dialog is open.
    var pendingPayload by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val payload = pendingPayload
        pendingPayload = null
        if (payload != null && result.resultCode == Activity.RESULT_OK) {
            confirm(Json.decodeFromString<DeletionTargets>(payload))
            started()
        }
    }
    return { targets ->
        when {
            pendingPayload != null -> Unit

            targets.contentUris.size > MAX_TRASH_REQUEST ->
                Toast
                    .makeText(
                        context,
                        context.getString(R.string.delete_phone_limit, MAX_TRASH_REQUEST),
                        Toast.LENGTH_LONG,
                    ).show()

            targets.contentUris.isEmpty() -> {
                confirm(targets)
                started()
            }

            else -> try {
                val request = MediaStore.createTrashRequest(
                    context.contentResolver,
                    targets.contentUris.map(Uri::parse),
                    true,
                )
                pendingPayload = Json.encodeToString(targets)
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: IllegalArgumentException) {
                // MediaStore rejects items that disappeared or are not media rows.
                pendingPayload = null
                Toast.makeText(context, R.string.delete_phone_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Confirms what goes where; the scope choice appears only when some objects have both copies.
 * Confirming before the server answered about its trash deletes only phone copies.
 */
@Composable
internal fun DeleteSelectionDialog(
    plan: DeletionPlan,
    cloudTrash: CloudTrashStatus,
    onDismiss: () -> Unit,
    onConfirm: (DeletionScope) -> Unit,
) {
    var scope by rememberSaveable { mutableStateOf(DeletionScope.Everywhere) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.delete_title, plan.total, plan.total)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.phoneOnly > 0) {
                    Text(stringResource(R.string.delete_phone_only, plan.phoneOnly))
                }
                if (plan.cloudOnly > 0) {
                    Text(stringResource(R.string.delete_cloud_only, plan.cloudOnly))
                }
                if (plan.merged > 0) {
                    Text(stringResource(R.string.delete_merged, plan.merged))
                    Column(Modifier.selectableGroup()) {
                        DeletionScope.entries.forEach { option ->
                            ScopeOption(
                                label = stringResource(option.label),
                                selected = option == scope,
                                onSelect = { scope = option },
                            )
                        }
                    }
                }
                if (plan.touchesPhone(scope)) {
                    Note(stringResource(R.string.delete_phone_trash_note)) {
                        Icon(painterResource(R.drawable.ic_phone), contentDescription = null)
                    }
                }
                if (plan.touchesCloud(scope)) {
                    CloudTrashNote(cloudTrash)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(scope) },
                enabled = plan.touchesPhone(scope) ||
                    (plan.touchesCloud(scope) && cloudTrash.allowsCloud),
            ) { Text(stringResource(R.string.delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_add_cancel)) }
        },
    )
}

@Composable
private fun CloudTrashNote(status: CloudTrashStatus) {
    val warning = status == CloudTrashStatus.Missing || status == CloudTrashStatus.Unreachable
    Note(
        text = stringResource(status.note),
        color = if (warning) MaterialTheme.colorScheme.error else Color.Unspecified,
    ) {
        when (status) {
            CloudTrashStatus.Checking -> CircularProgressIndicator(strokeWidth = 2.dp)

            CloudTrashStatus.Available ->
                Icon(painterResource(R.drawable.ic_cloud), contentDescription = null)

            CloudTrashStatus.Missing, CloudTrashStatus.Unreachable -> Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Note(text: String, color: Color = Color.Unspecified, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text,
            modifier = Modifier.padding(start = 8.dp),
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private val CloudTrashStatus.note: Int
    get() = when (this) {
        CloudTrashStatus.Checking -> R.string.delete_cloud_checking_note
        CloudTrashStatus.Available -> R.string.delete_cloud_trash_note
        CloudTrashStatus.Missing -> R.string.delete_cloud_permanent_note
        CloudTrashStatus.Unreachable -> R.string.delete_cloud_unreachable_note
    }

private val DeletionScope.label: Int
    get() = when (this) {
        DeletionScope.Everywhere -> R.string.delete_scope_everywhere
        DeletionScope.PhoneOnly -> R.string.delete_scope_phone
        DeletionScope.CloudOnly -> R.string.delete_scope_cloud
    }
