package com.sky22333.frpandroid.core.runtime

import com.sky22333.frpandroid.core.frp.FrpResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpRuntimePolicyTest {
    @Test
    fun successfulStartIsSatisfiedAndNotRetried() {
        val result = FrpResult(code = null, message = "")

        assertTrue(FrpRuntimePolicy.isStartSatisfied(result))
        assertFalse(FrpRuntimePolicy.shouldRetryStart(result, autoRetryEnabled = true))
    }

    @Test
    fun alreadyRunningIsSatisfiedAndNotRetried() {
        val result = FrpResult.fromRaw("ALREADY_RUNNING: client-a")

        assertTrue(FrpRuntimePolicy.isStartSatisfied(result))
        assertFalse(FrpRuntimePolicy.shouldRetryStart(result, autoRetryEnabled = true))
    }

    @Test
    fun invalidTomlIsNotRetried() {
        val result = FrpResult.fromRaw("INVALID_TOML: missing serverAddr")

        assertFalse(FrpRuntimePolicy.isStartSatisfied(result))
        assertFalse(FrpRuntimePolicy.shouldRetryStart(result, autoRetryEnabled = true))
    }

    @Test
    fun invalidTempDirIsNotRetried() {
        val result = FrpResult.fromRaw("INVALID_TEMP_DIR: bad cache")

        assertFalse(FrpRuntimePolicy.isStartSatisfied(result))
        assertFalse(FrpRuntimePolicy.shouldRetryStart(result, autoRetryEnabled = true))
    }

    @Test
    fun startFailureIsRetriedOnlyWhenAutoRetryEnabled() {
        val result = FrpResult.fromRaw("START_FAILED: network unavailable")

        assertFalse(FrpRuntimePolicy.isStartSatisfied(result))
        assertTrue(FrpRuntimePolicy.shouldRetryStart(result, autoRetryEnabled = true))
        assertFalse(FrpRuntimePolicy.shouldRetryStart(result, autoRetryEnabled = false))
    }
}
