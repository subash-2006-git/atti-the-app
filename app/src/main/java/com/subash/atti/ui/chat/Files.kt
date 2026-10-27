package com.subash.atti.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.subash.atti.R
import com.subash.atti.data.FileNames
import com.subash.atti.data.model.Message
import java.io.File

/**
 * A shared file: type badge, name, size, and a progress bar while it travels.
 * Tap to open it in another app; received files also get a Save button.
 */
@Composable
fun FileBubble(message: Message, senderName: String, onOpen: () -> Unit, onSave: () -> Unit) {
    val document = message.document ?: return
    val mine = message.fromMe
    val colors = MaterialTheme.colorScheme
    val background = if (mine) colors.primary else colors.surfaceContainerHigh
    val foreground = if (mine) colors.onPrimary else colors.onSurface
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

    val ready = document.file != null && document.progress == null && !message.failed
    val extension = FileNames.extension(document.name).uppercase()
    val size = FileNames.formatSize(document.size)
    val details = when {
        message.failed -> if (mine) "Not sent" else "Couldn't receive"
        document.progress != null -> {
            val done = FileNames.formatSize((document.size * document.progress).toLong())
            "$done of $size"
        }
        extension.isNotEmpty() -> "$size · $extension"
        else -> size
    }
    val label = (if (mine) "File you sent" else "File from $senderName") + ", ${document.name}, $details"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(background)
                .clickable(enabled = ready, onClickLabel = "Open file", onClick = onOpen)
                .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 6.dp)
                .semantics { contentDescription = label },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(foreground.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (message.failed) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = foreground)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painterResource(R.drawable.ic_file),
                                contentDescription = null,
                                tint = foreground,
                                modifier = Modifier.size(if (extension.isEmpty()) 24.dp else 18.dp),
                            )
                            if (extension.isNotEmpty()) {
                                Text(
                                    extension.take(4),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = foreground,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.widthIn(min = 120.dp, max = 210.dp)) {
                    Text(
                        document.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.failed) colors.error else foreground.copy(alpha = 0.75f),
                    )
                }
            }
            document.progress?.let { progress ->
                if (!message.failed) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        color = foreground,
                        trackColor = foreground.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!mine && ready) {
                    TextButton(onClick = onSave, contentPadding = SaveButtonPadding) {
                        Text("Save", color = foreground)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private val SaveButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)

/** Hands the file to another app to view. Returns false if no installed app can open it. */
fun openFile(context: Context, file: File, mimeType: String): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i("Files", "no app opens $mimeType", e)
        false
    }
}

/** The system "Save as" screen, opened with the received file's own type. */
class SaveFileAs : ActivityResultContracts.CreateDocument("application/octet-stream") {
    var mimeType: String = "application/octet-stream"

    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).setType(mimeType)
}
