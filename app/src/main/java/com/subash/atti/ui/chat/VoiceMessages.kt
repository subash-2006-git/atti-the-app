package com.subash.atti.ui.chat

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.subash.atti.R
import com.subash.atti.data.model.Message
import java.io.File

/** Plays one voice message at a time; tapping another stops the first. */
class VoicePlayer {
    var activeId by mutableStateOf<Long?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    private var player: MediaPlayer? = null

    fun toggle(messageId: Long, file: File) {
        val current = player
        if (activeId == messageId && current != null) {
            if (isPlaying) current.pause() else current.start()
            isPlaying = !isPlaying
            return
        }
        release()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { release() }
                prepare()
                start()
            }
            activeId = messageId
            isPlaying = true
        } catch (e: Exception) {
            Log.w("VoicePlayer", "couldn't play voice message", e)
            release()
        }
    }

    /** Call often while playing so the waveform and timer follow along. */
    fun updatePosition() {
        positionMs = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L
    }

    fun pause() {
        if (isPlaying) {
            player?.pause()
            isPlaying = false
        }
    }

    fun release() {
        player?.release()
        player = null
        activeId = null
        isPlaying = false
        positionMs = 0L
    }
}

/** A voice message, Instagram style: play button, waveform that fills as it plays, time. */
@Composable
fun VoiceBubble(message: Message, player: VoicePlayer, senderName: String) {
    val voice = message.voice ?: return
    val mine = message.fromMe
    val colors = MaterialTheme.colorScheme
    val background = if (mine) colors.primary else colors.surfaceContainerHigh
    val foreground = if (mine) colors.onPrimary else colors.onSurface
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

    val active = player.activeId == message.id
    val ready = voice.file != null && voice.progress == null && !message.failed
    val played = if (active && voice.durationMs > 0) (player.positionMs / voice.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val shownTime = if (active) player.positionMs else voice.durationMs
    val label = (if (mine) "Voice message you sent" else "Voice message from $senderName") +
        ", ${formatDuration(voice.durationMs)}"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(background, shape)
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                .semantics { contentDescription = label },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    message.failed -> Box40 {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = foreground)
                    }

                    voice.progress != null -> Box40 {
                        CircularProgressIndicator(
                            progress = { voice.progress.coerceAtLeast(0.05f) },
                            color = foreground,
                            trackColor = foreground.copy(alpha = 0.25f),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    else -> IconButton(
                        onClick = { voice.file?.let { player.toggle(message.id, it) } },
                        enabled = ready,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = foreground),
                        modifier = Modifier.size(40.dp),
                    ) {
                        if (active && player.isPlaying) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Waveform(
                    levels = voice.waveform,
                    played = played,
                    playedColor = foreground,
                    restColor = foreground.copy(alpha = 0.4f),
                    modifier = Modifier.width(150.dp).height(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    formatDuration(shownTime),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = foreground,
                )
            }
            Text(
                if (message.failed) {
                    if (mine) "Not sent" else "Couldn't receive"
                } else {
                    formatTime(message.timestamp)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (message.failed) colors.error else foreground.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun Box40(content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { content() }
}

/** Shown in place of the message box while recording: cancel, live level, timer, send. */
@Composable
fun RecordingBar(
    elapsedMs: Long,
    levels: List<Float>,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "recording").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot",
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Delete, contentDescription = "Cancel recording", tint = MaterialTheme.colorScheme.error)
            }
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(pulse)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatDuration(elapsedMs),
                style = MaterialTheme.typography.titleMedium.tabular(),
                modifier = Modifier.semantics { contentDescription = "Recording, ${formatDuration(elapsedMs)}" },
            )
            Spacer(Modifier.width(12.dp))
            Waveform(
                levels = levels,
                played = 1f,
                playedColor = MaterialTheme.colorScheme.primary,
                restColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).height(32.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message")
            }
        }
    }
}

/** Rounded loudness bars; the first [played] fraction uses [playedColor]. */
@Composable
fun Waveform(
    levels: List<Float>,
    played: Float,
    playedColor: Color,
    restColor: Color,
    modifier: Modifier = Modifier,
) {
    val bars = levels.ifEmpty { List(EMPTY_BARS) { 0f } }
    Canvas(modifier) {
        val slot = size.width / bars.size
        val barWidth = (slot * 0.55f).coerceAtLeast(1.5f)
        bars.forEachIndexed { index, level ->
            val height = size.height * (MIN_BAR + (1 - MIN_BAR) * level.coerceIn(0f, 1f))
            val x = index * slot + (slot - barWidth) / 2
            val isPlayed = (index + 0.5f) / bars.size <= played
            drawRoundRect(
                color = if (isPlayed) playedColor else restColor,
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val EMPTY_BARS = 30
private const val MIN_BAR = 0.12f

/** 75 000 ms → "1:15". */
fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Digits of equal width, so timers don't jitter as they count. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
