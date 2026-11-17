package com.affclix.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallCheckTest {
    @Test fun recognisesTheMmpSilentPush() {
        // The MMP's FCM data message: {"affclix_uninstall_check": "1"}
        assertTrue(AffclixSDK.isUninstallCheck(mapOf("affclix_uninstall_check" to "1")))
        assertFalse(AffclixSDK.isUninstallCheck(mapOf("title" to "Sale!")))
        assertFalse(AffclixSDK.isUninstallCheck(emptyMap()))
    }
}
