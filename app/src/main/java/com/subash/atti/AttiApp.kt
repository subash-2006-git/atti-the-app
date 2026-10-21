package com.subash.atti

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subash.atti.data.model.NearbyEvent
import com.subash.atti.data.model.PeerStatus
import com.subash.atti.ui.chat.ChatScreen
import com.subash.atti.ui.name.NameScreen
import com.subash.atti.ui.permissions.PermissionGate
import com.subash.atti.ui.scan.ConnectingDialog
import com.subash.atti.ui.scan.IncomingRequestDialog
import com.subash.atti.ui.scan.ScanScreen
import com.subash.atti.ui.scan.ScanViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object NameRoute
@Serializable object ScanRoute
@Serializable object ChatRoute

@Composable
fun AttiApp() {
    val app = LocalContext.current.applicationContext as AttiApplication
    val scope = rememberCoroutineScope()

    // Read the saved name once to decide the first screen; null while loading.
    val start by produceState<Any?>(initialValue = null) {
        value = if (app.userPrefs.name.first() == null) NameRoute else ScanRoute
    }

    val status by app.nearby.status.collectAsStateWithLifecycle()
    val incoming by app.nearby.incoming.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background) {
        val startRoute = start ?: return@Surface
        val navController = rememberNavController()

        // The chat screen follows the link: open it as soon as a link is up (whichever
        // side asked), and leave it once the chat has ended.
        LaunchedEffect(status) {
            val onChat = navController.currentDestination?.hasRoute<ChatRoute>() == true
            val inChat = status is PeerStatus.Connected || status is PeerStatus.Disconnected
            if (inChat && !onChat) navController.navigate(ChatRoute)
            if (!inChat && onChat) navController.popBackStack()
        }

        // A request can arrive while any screen is open, so the popup lives here.
        incoming?.let { peer ->
            IncomingRequestDialog(
                peer = peer,
                onAccept = app.nearby::acceptIncoming,
                onDecline = app.nearby::declineIncoming,
            )
        }
        when (val s = status) {
            is PeerStatus.Requesting -> ConnectingDialog(s.peer, waitingForAnswer = true, onCancel = app.nearby::cancelRequest)
            is PeerStatus.Accepting -> ConnectingDialog(s.peer, waitingForAnswer = false, onCancel = { })
            else -> Unit
        }

        NavHost(navController = navController, startDestination = startRoute) {
            composable<NameRoute> {
                NameScreen(onContinue = { name ->
                    scope.launch {
                        app.userPrefs.saveName(name)
                        navController.navigate(ScanRoute) {
                            popUpTo(NameRoute) { inclusive = true }
                        }
                    }
                })
            }
            composable<ScanRoute> {
                PermissionGate {
                    val vm = viewModel { ScanViewModel(app.nearby, app.userPrefs) }
                    val myName by vm.myName.collectAsStateWithLifecycle()
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    val snackbarHostState = remember { SnackbarHostState() }

                    LaunchedEffect(Unit) {
                        app.nearby.events.collect { event ->
                            snackbarHostState.showSnackbar(event.toMessage())
                        }
                    }

                    ScanScreen(
                        myName = myName,
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onRescan = vm::rescan,
                        onPeerClick = app.nearby::requestChat,
                        debugActions = {
                            if (BuildConfig.DEBUG) DebugIncomingButton(app.nearby::simulateIncomingRequest)
                        },
                    )
                }
            }
            composable<ChatRoute> {
                val messages by app.nearby.messages.collectAsStateWithLifecycle()
                val peer = when (val s = status) {
                    is PeerStatus.Connected -> s.peer
                    is PeerStatus.Disconnected -> s.peer
                    else -> null
                }
                // peer is null only for the moment between a chat ending and the screen closing.
                if (peer != null) {
                    ChatScreen(
                        peer = peer,
                        connected = status is PeerStatus.Connected,
                        messages = messages,
                        onSend = app.nearby::sendMessage,
                        onBack = app.nearby::leaveChat,
                    )
                }
            }
        }
    }
}

private fun NearbyEvent.toMessage(): String = when (this) {
    is NearbyEvent.Declined -> "${peer.name} declined your request"
    is NearbyEvent.Busy -> "${peer.name} is busy in another chat"
    is NearbyEvent.Failed -> "Couldn't connect to ${peer.name}. Try again."
    is NearbyEvent.NoAnswer -> "${peer.name} didn't answer. Try again later."
    is NearbyEvent.Error -> "$message. Try Rescan."
}

/** Debug builds only: makes Test Bot send us a chat request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugIncomingButton(onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text("Simulate a request from Test Bot") } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Default.Notifications, contentDescription = "Simulate a request from Test Bot")
        }
    }
}
