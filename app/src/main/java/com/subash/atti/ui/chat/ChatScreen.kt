package com.subash.atti.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.subash.atti.R
import com.subash.atti.data.VoiceRecorder
import com.subash.atti.data.model.ChatActivity
import com.subash.atti.data.model.Message
import com.subash.atti.data.model.Peer
import com.subash.atti.data.model.PeerStatus
import com.subash.atti.ui.scan.Avatar
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peer: Peer,
    /** Null while connected; otherwise why the chat ended. */
    disconnected: PeerStatus.Disconnected.Reason?,
    messages: List<Message>,
    /** What the other person is doing right now. */
    peerActivity: ChatActivity,
    snackbarHostState: SnackbarHostState,
    onSend: (String) -> Unit,
    onSendImage: (Uri) -> Unit,
    onSendVoice: (VoiceRecorder.Recording) -> Unit,
    /** Tells the other phone whether we're typing or recording. */
    onActivity: (ChatActivity) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = disconnected == null
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Android's photo picker: no storage permission needed.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onSendImage)
    }
    var viewingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val viewing = messages.firstOrNull { it.id == viewingId && it.image?.file != null }

    val player = remember { VoicePlayer() }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    val liveLevels = remember { mutableStateListOf<Float>() }

    fun say(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    fun startRecording() {
        player.pause()
        if (recorder.start()) {
            liveLevels.clear()
            recordedMs = 0L
            recording = true
        } else {
            say("Couldn't use the microphone. Is it busy with a call?")
        }
    }

    fun finishRecording(send: Boolean) {
        if (!recording) return
        recording = false
        onActivity(ChatActivity.NONE)
        if (!send) {
            recorder.cancel()
            return
        }
        val result = recorder.stop()
        if (result != null) onSendVoice(result) else say("Too short to send. Record for at least a second.")
    }

    val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else say("Allow the microphone in Settings to send voice messages.")
    }

    // Sample the mic for the live waveform and timer; tell the other side we're recording.
    LaunchedEffect(recording) {
        while (recording) {
            recordedMs = recorder.elapsedMs
            liveLevels += recorder.sampleLevel()
            if (liveLevels.size > LIVE_BARS) liveLevels.removeAt(0)
            onActivity(ChatActivity.RECORDING)
            if (recordedMs >= VoiceRecorder.MAX_DURATION_MS) finishRecording(send = true)
            delay(RECORD_TICK_MS)
        }
    }

    // While typing, say so; after a few quiet seconds, or once the box is empty, stop saying so.
    LaunchedEffect(draft) {
        if (recording) return@LaunchedEffect
        if (draft.isBlank()) {
            onActivity(ChatActivity.NONE)
        } else {
            onActivity(ChatActivity.TYPING)
            delay(TYPING_IDLE_MS)
            onActivity(ChatActivity.NONE)
        }
    }

    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            player.updatePosition()
            delay(PLAYER_TICK_MS)
        }
    }

    // A chat that ends mid-recording throws the recording away.
    LaunchedEffect(connected) {
        if (!connected) finishRecording(send = false)
    }

    // Leaving the app stops playback and drops an unfinished recording.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.pause()
        finishRecording(send = false)
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            recorder.cancel()
        }
    }

    // The list is drawn bottom-up, so item 0 is the newest message; scrolling to it shows
    // the end of a message even when it is taller than the screen.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // Reveal the typing bubble, but don't yank someone who has scrolled up to read.
    LaunchedEffect(peerActivity) {
        if (peerActivity != ChatActivity.NONE && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave chat")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(peer.name)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    !connected -> "Disconnected"
                                    peerActivity == ChatActivity.TYPING -> "typing…"
                                    peerActivity == ChatActivity.RECORDING -> "recording audio…"
                                    else -> "Connected"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
        ) {
            if (disconnected != null) DisconnectedBanner(peer, disconnected)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && peerActivity == ChatActivity.NONE) {
                    Text(
                        "Say hi to ${peer.name}. Messages, photos and voice messages aren't saved and disappear when you leave this chat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (connected && peerActivity != ChatActivity.NONE) {
                        item(key = "typing") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                TypingBubble(peerActivity, peer.name)
                            }
                        }
                    }
                    items(messages.asReversed(), key = { it.id }) { message ->
                        when {
                            message.image != null -> Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
                            ) {
                                PhotoBubble(message, senderName = peer.name, onOpen = { viewingId = message.id })
                            }

                            message.voice != null -> VoiceBubble(message, player, senderName = peer.name)

                            else -> MessageBubble(message)
                        }
                    }
                }
            }
            if (recording) {
                RecordingBar(
                    elapsedMs = recordedMs,
                    levels = liveLevels,
                    onCancel = { finishRecording(send = false) },
                    onSend = { finishRecording(send = true) },
                )
            } else {
                InputBar(
                    draft = draft,
                    onDraftChange = { draft = it.take(Message.MAX_LENGTH) },
                    enabled = connected,
                    onPickPhoto = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRecord = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) startRecording() else askForMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSend = {
                        onSend(draft)
                        draft = ""
                    },
                )
            }
        }
    }

    viewing?.let { message ->
        PhotoViewer(
            message = message,
            title = if (message.fromMe) "You" else peer.name,
            onDismiss = { viewingId = null },
        )
    }
}

private const val LIVE_BARS = 40
private const val RECORD_TICK_MS = 100L
private const val PLAYER_TICK_MS = 50L

/** Stop showing "typing…" to the other person after this long without a keystroke. */
private const val TYPING_IDLE_MS = 5_000L

@Composable
private fun DisconnectedBanner(peer: Peer, reason: PeerStatus.Disconnected.Reason) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                when (reason) {
                    PeerStatus.Disconnected.Reason.OUT_OF_RANGE -> "Disconnected – out of range"
                    PeerStatus.Disconnected.Reason.LEFT -> "${peer.name} left the chat"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Go back to find them again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val mine = message.fromMe
    val colors = MaterialTheme.colorScheme
    val shape = if (mine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    val background = if (mine) colors.primary else colors.surfaceContainerHigh
    val foreground = if (mine) colors.onPrimary else colors.onSurface

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(background, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = foreground)
            Spacer(Modifier.height(2.dp))
            Text(
                if (message.failed) "Not sent" else formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.failed) colors.error else foreground.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    onPickPhoto: () -> Unit,
    onRecord: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                placeholder = { Text(if (enabled) "Message" else "Can't send while disconnected") },
                // Like Instagram, the photo button sits in the box and steps aside while typing.
                trailingIcon = if (draft.isEmpty()) {
                    {
                        Row {
                            IconButton(onClick = onRecord, enabled = enabled) {
                                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Record a voice message")
                            }
                            IconButton(onClick = onPickPhoto, enabled = enabled) {
                                Icon(painterResource(R.drawable.ic_photo), contentDescription = "Send a photo")
                            }
                        }
                    }
                } else {
                    null
                },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

internal fun formatTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
