package com.subash.atti

import android.Manifest
import com.subash.atti.util.PermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHelperTest {
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @Test fun `Android 8 to 11 asks for location only`() {
        assertEquals(listOf(fine, coarse), PermissionHelper.requiredPermissions(26))
    }

    @Test fun `Android 12 adds the Bluetooth permissions`() {
        val perms = PermissionHelper.requiredPermissions(31)
        assertTrue(perms.containsAll(listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            fine,
            coarse,
        )))
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in perms)
    }

    @Test fun `Android 13 and newer add nearby Wi-Fi and still need location`() {
        // Nearby fails discovery with MISSING_PERMISSION_ACCESS_COARSE_LOCATION without it.
        val perms = PermissionHelper.requiredPermissions(36)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in perms)
        assertTrue(coarse in perms)
    }

    @Test fun `approximate location is enough from Android 12`() {
        val granted = PermissionHelper.requiredPermissions(36) - fine
        assertEquals(emptyList<String>(), PermissionHelper.filterMissing(PermissionHelper.requiredPermissions(36), 36) { it in granted })
    }

    @Test fun `before Android 12 precise location is still required`() {
        val missing = PermissionHelper.filterMissing(listOf(fine, coarse), 30) { it == coarse }
        assertEquals(listOf(fine), missing)
    }

    @Test fun `no location at all is reported as missing`() {
        val missing = PermissionHelper.filterMissing(PermissionHelper.requiredPermissions(36), 36) { it != fine && it != coarse }
        assertEquals(listOf(fine, coarse), missing)
    }
}
