package com.subash.atti

import android.Manifest
import com.subash.atti.util.PermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHelperTest {
    @Test fun `Android 8 to 11 asks for location only`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PermissionHelper.requiredPermissions(26),
        )
        assertTrue(PermissionHelper.needsLocationServices(30))
    }

    @Test fun `Android 12 adds the Bluetooth permissions and keeps location`() {
        val perms = PermissionHelper.requiredPermissions(31)
        assertTrue(perms.containsAll(listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )))
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in perms)
    }

    @Test fun `Android 13 and newer swap location for nearby Wi-Fi devices`() {
        val perms = PermissionHelper.requiredPermissions(33)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in perms)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertFalse(PermissionHelper.needsLocationServices(36))
    }
}
