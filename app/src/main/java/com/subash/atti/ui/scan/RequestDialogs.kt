package com.subash.atti.ui.scan

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.subash.atti.data.model.Peer

/** Shown on the other phone: "Subash wants to chat — Accept / Decline". */
@Composable
fun IncomingRequestDialog(peer: Peer, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        icon = { Avatar(peer.name) },
        title = { Text("${peer.name} wants to chat") },
        text = { Text("Accept to open a private chat with ${peer.name}. It works only while you're both nearby.") },
        confirmButton = { Button(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

/** Shown while we wait for the other person, or while the link opens. */
@Composable
fun ConnectingDialog(peer: Peer, waitingForAnswer: Boolean, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(if (waitingForAnswer) "Waiting for ${peer.name}" else "Connecting to ${peer.name}") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(16.dp))
                Text(if (waitingForAnswer) "Asking them to accept your request…" else "Opening the chat…")
            }
        },
        confirmButton = {
            if (waitingForAnswer) TextButton(onClick = onCancel) { Text("Cancel request") }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}
