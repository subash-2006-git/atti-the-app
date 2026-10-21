package com.subash.atti

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subash.atti.ui.name.NameScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object NameRoute
@Serializable object ScanRoute
@Serializable data class ChatRoute(val peerName: String)

@Composable
fun AttiApp() {
    val app = LocalContext.current.applicationContext as AttiApplication
    val scope = rememberCoroutineScope()

    // Read the saved name once to decide the first screen; null while loading.
    val start by produceState<Any?>(initialValue = null) {
        value = if (app.userPrefs.name.first() == null) NameRoute else ScanRoute
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        val startRoute = start ?: return@Surface
        val navController = rememberNavController()
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
            composable<ScanRoute> { Placeholder("Scan") }
            composable<ChatRoute> { Placeholder("Chat") }
        }
    }
}

@Composable
private fun Placeholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
