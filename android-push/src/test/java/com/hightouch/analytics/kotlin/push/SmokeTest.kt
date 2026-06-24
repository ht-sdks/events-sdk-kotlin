package com.hightouch.analytics.kotlin.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Smoke test verifying the android-push module compiles and runs its test infrastructure.
 * Real coverage lands as subsequent stacked PRs add functionality.
 */
class SmokeTest {
    @Test
    fun moduleIsAlive() {
        assertEquals(4, 2 + 2)
    }
}
