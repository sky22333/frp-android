package com.sky22333.frpandroid.core.frp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpResultTest {
    @Test
    fun emptyStringMeansSuccess() {
        val result = FrpResult.fromRaw("")

        assertTrue(result.isSuccess)
        assertNull(result.code)
        assertEquals("", result.message)
    }

    @Test
    fun errorCodeIsParsedBeforeColon() {
        val result = FrpResult.fromRaw("INVALID_TOML: missing serverAddr")

        assertEquals("INVALID_TOML", result.code)
        assertEquals("INVALID_TOML: missing serverAddr", result.message)
    }

    @Test
    fun codeOnlyErrorIsPreserved() {
        val result = FrpResult.fromRaw("ALREADY_RUNNING")

        assertEquals("ALREADY_RUNNING", result.code)
        assertEquals("ALREADY_RUNNING", result.message)
        assertTrue(result.isAlreadyRunning)
    }

    @Test
    fun stopFailedIsNoOpOnlyForAlreadyStoppedMessages() {
        val stopped = FrpResult.fromRaw("STOP_FAILED: instance not running")
        val realFailure = FrpResult.fromRaw("STOP_FAILED: failed to close socket")

        assertTrue(stopped.isAlreadyStopped)
        org.junit.Assert.assertFalse(realFailure.isAlreadyStopped)
    }

    @Test
    fun invalidTempDirIsRecognized() {
        val result = FrpResult.fromRaw("INVALID_TEMP_DIR: failed to create cache dir")

        assertEquals("INVALID_TEMP_DIR", result.code)
        assertTrue(result.isInvalidTempDir)
    }
}
