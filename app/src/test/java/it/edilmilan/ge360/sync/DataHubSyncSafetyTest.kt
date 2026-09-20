package it.edilmilan.ge360.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataHubSyncSafetyTest {
    @Test fun freshDeviceCanInitializeEmptyServer() {
        assertTrue(DataHubSyncSafety.canPush(false, 0, 0))
    }

    @Test fun freshOrReinstalledDeviceCannotOverwriteExistingServer() {
        assertFalse(DataHubSyncSafety.canPush(false, 0, 4))
    }

    @Test fun knownMatchingRevisionCanPush() {
        assertTrue(DataHubSyncSafety.canPush(true, 7, 7))
    }

    @Test fun divergedRevisionIsBlocked() {
        assertFalse(DataHubSyncSafety.canPush(true, 6, 7))
    }
}
