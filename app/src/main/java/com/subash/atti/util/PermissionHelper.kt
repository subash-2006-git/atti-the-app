package com.subash.atti.util

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** The phone settings Nearby Connections needs switched on. */
enum class Radio { BLUETOOTH, WIFI, LOCATION }

/**
 * Knows which permissions and settings Nearby Connections needs on each Android version.
 * Must match the <uses-permission> entries in AndroidManifest.xml.
 */
object PermissionHelper {

    fun requiredPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdk >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // Nearby Connections refuses to discover without location access on every version,
        // including Android 13+ (it reports MISSING_PERMISSION_ACCESS_COARSE_LOCATION).
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /**
     * The required permissions not yet granted. From Android 12 the user may allow only
     * approximate location; Nearby is satisfied with that, so precise isn't required.
     */
    fun missingPermissions(context: Context, sdk: Int = Build.VERSION.SDK_INT): List<String> =
        filterMissing(requiredPermissions(sdk), sdk) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    internal fun filterMissing(permissions: List<String>, sdk: Int, isGranted: (String) -> Boolean): List<String> {
        val missing = permissions.filterNot(isGranted)
        val approximateIsEnough = sdk >= Build.VERSION_CODES.S && Manifest.permission.ACCESS_COARSE_LOCATION !in missing
        return if (approximateIsEnough) missing - Manifest.permission.ACCESS_FINE_LOCATION else missing
    }

    /** True when the user picked "Don't ask again" (or the system stopped showing the dialog). */
    fun isPermanentlyDenied(activity: Activity, denied: List<String>): Boolean =
        denied.any { !activity.shouldShowRequestPermissionRationale(it) }

    fun radiosOff(context: Context): List<Radio> = buildList {
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetooth?.isEnabled != true) add(Radio.BLUETOOTH)

        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled != true) add(Radio.WIFI)

        // Bluetooth scanning returns nothing while Location is switched off.
        val location = context.getSystemService(LocationManager::class.java)
        if (location == null || !LocationManagerCompat.isLocationEnabled(location)) add(Radio.LOCATION)
    }

    fun hasPlayServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Intent that lets the user switch a radio on (Bluetooth is handled with ACTION_REQUEST_ENABLE). */
    fun settingsIntent(radio: Radio): Intent = when (radio) {
        Radio.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Radio.WIFI ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
        Radio.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
