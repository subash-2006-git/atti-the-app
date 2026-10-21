package com.subash.atti.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.subash.atti.data.model.Message

private val PhotoShape = RoundedCornerShape(22.dp)
private const val MIN_ASPECT = 0.6f
private const val MAX_ASPECT = 1.8f

/**
 * A photo in the chat, Instagram-DM style: no bubble, just the rounded image.
 * While it's sending or arriving it shows dimmed with a progress ring.
 */
@Composable
fun PhotoBubble(message: Message, senderName: String, onOpen: () -> Unit) {
    val photo = message.image ?: return
    val ready = photo.file != null && photo.progress == null && !message.failed
    val label = if (message.fromMe) "Photo you sent" else "Photo from $senderName"

    Column(horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
        Box(
            Modifier
                .width(240.dp)
                .aspectRatio(photo.aspectRatio.coerceIn(MIN_ASPECT, MAX_ASPECT))
                .clip(PhotoShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = ready, onClickLabel = "View photo", onClick = onOpen)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (photo.file != null) {
                AsyncImage(
                    model = photo.file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when {
                message.failed -> PhotoOverlay {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (message.fromMe) "Not sent" else "Couldn't receive",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }

                photo.progress != null -> PhotoOverlay {
                    // Show a spinner until bytes start flowing, then real progress.
                    if (photo.progress > 0f) {
                        CircularProgressIndicator(
                            progress = { photo.progress },
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(44.dp),
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            formatTime(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun PhotoOverlay(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** Full-screen photo on black. Pinch or double-tap to zoom; Back or ✕ to close. */
@Composable
fun PhotoViewer(message: Message, title: String, onDismiss: () -> Unit) {
    val file = message.image?.file ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = file,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                            }
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .safeDrawingPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close photo", tint = Color.White)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        formatTime(message.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f
