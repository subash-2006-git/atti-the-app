package com.subash.atti.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.subash.atti.R
import com.subash.atti.data.model.ChatActivity

/** Their side of the chat: three bouncing dots while typing, a mic and dots while recording. */
@Composable
fun TypingBubble(activity: ChatActivity, name: String) {
    val colors = MaterialTheme.colorScheme
    val description = if (activity == ChatActivity.RECORDING) "$name is recording audio" else "$name is typing"
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        Modifier
            .background(colors.surfaceContainerHigh, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activity == ChatActivity.RECORDING) {
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(DOT_MS),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * DOT_MS / 3),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(7.dp)
                    .graphicsLayer {
                        translationY = -4.dp.toPx() * lift
                        alpha = 0.4f + 0.6f * lift
                    }
                    .background(colors.onSurfaceVariant, CircleShape),
            )
        }
    }
}

private const val DOT_MS = 450
