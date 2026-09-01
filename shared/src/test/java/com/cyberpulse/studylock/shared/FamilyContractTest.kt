package com.cyberpulse.studylock.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyContractTest {
    @Test
    fun sessionDurationHonorsProductLimits() {
        assertTrue(FamilyRules.validDuration(25))
        assertTrue(FamilyRules.validDuration(300))
        assertFalse(FamilyRules.validDuration(24))
        assertFalse(FamilyRules.validDuration(301))
    }

    @Test
    fun pairingCodeHasSixDigitsAndExpires() {
        val code = FamilyRules.newPairingCode(nowMillis = 1_000L)
        assertTrue(code.isValid(2_000L))
        assertFalse(code.isValid(1_000L + FamilyRules.PAIRING_CODE_LIFETIME_MILLIS))
    }
}
