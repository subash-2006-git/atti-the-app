package com.subash.atti.ui.permissions

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.subash.atti.util.PermissionHelper
import com.subash.atti.util.Radio
import kotlinx.coroutines.delay

/**
 * Shows [content] only once Play Services is present, every permission is granted
 * and Bluetooth / Wi-Fi / Location are on. Re-checks whenever the app comes back
 * to the foreground (e.g. after the user visits Settings).
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity

    // Bumped on resume and after each request so the checks below re-run.
    var checkTick by remember { mutableIntStateOf(0) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        checkTick++
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Judge only what's still missing: "approximate location" leaves precise denied, and that's fine.
        val denied = PermissionHelper.missingPermissions(context)
        permanentlyDenied = denied.isNotEmpty() && PermissionHelper.isPermanentlyDenied(activity, denied)
        checkTick++
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkTick++ }

    fun requestPermissions() {
        askedOnce = true
        permissionLauncher.launch(PermissionHelper.requiredPermissions().toTypedArray())
    }

    // Reading checkTick makes these recompute on every bump.
    val hasPlayServices = remember(checkTick) { PermissionHelper.hasPlayServices(context) }
    val missing = remember(checkTick) { PermissionHelper.missingPermissions(context) }
    val radiosOff = remember(checkTick) {
        if (missing.isEmpty()) PermissionHelper.radiosOff(context) else emptyList()
    }

    // Radios switched on from Quick Settings don't pause the app, so keep checking while we
    // wait. The effect restarts (and stops looping) once nothing is off any more.
    val waitingForRadios = radiosOff.isNotEmpty()
    LaunchedEffect(waitingForRadios) {
        if (!waitingForRadios) return@LaunchedEffect
        while (true) {
            delay(RADIO_RECHECK_MS)
            checkTick++
        }
    }

    // Ask in one go the first time the user reaches this point.
    LaunchedEffect(Unit) {
        if (!askedOnce && PermissionHelper.missingPermissions(context).isNotEmpty()) requestPermissions()
    }

    when {
        !hasPlayServices -> GateMessage(
            title = "Google Play Services needed",
            body = "ATTI uses Google Play Services to find nearby phones. Install or update it from the Play Store, then come back.",
        )

        missing.isNotEmpty() && permanentlyDenied -> GateMessage(
            title = "Allow nearby access in Settings",
            body = "ATTI can't ask again from here. Open Settings, go to Permissions and allow Nearby devices and Location.",
            primary = "Open Settings" to { context.startActivity(PermissionHelper.appSettingsIntent(context)) },
        )

        missing.isNotEmpty() -> GateMessage(
            title = "ATTI needs nearby access",
            body = "To find people around you without internet, ATTI uses Bluetooth and Wi-Fi to talk directly to their phone. " +
                "Android treats finding nearby phones as location access, so it asks for Location too — ATTI never reads or shares where you are.",
            primary = (if (askedOnce) "Try again" else "Allow access") to ::requestPermissions,
        )

        radiosOff.isNotEmpty() -> {
            val radio = radiosOff.first()
            GateMessage(
                title = "Turn on ${radio.label}",
                body = "ATTI needs ${radiosOff.joinToString(" and ") { it.label }} switched on to find and talk to nearby phones.",
                primary = "Turn on ${radio.label}" to {
                    if (radio == Radio.BLUETOOTH) {
                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } else {
                        context.startActivity(PermissionHelper.settingsIntent(radio))
                    }
                },
            )
        }

        else -> content()
    }
}

private const val RADIO_RECHECK_MS = 1_500L

private val Radio.label: String
    get() = when (this) {
        Radio.BLUETOOTH -> "Bluetooth"
        Radio.WIFI -> "Wi-Fi"
        Radio.LOCATION -> "Location"
    }

@Composable
private fun GateMessage(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (primary != null) {
                Spacer(Modifier.height(32.dp))
                Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(primary.first)
                }
            }
        }
    }
}
