package com.cyberpulse.studylock.parent

import org.junit.Assert.assertTrue
import org.junit.Test

class AppProfileTest {
    @Test
    fun profileHasEnoughCoreFeatures() {
        assertTrue(appSpec.name.isNotBlank())
        assertTrue(appSpec.features.size >= 4)
        assertTrue(appSpec.features.map { it.title }.distinct().size == appSpec.features.size)
    }
}
