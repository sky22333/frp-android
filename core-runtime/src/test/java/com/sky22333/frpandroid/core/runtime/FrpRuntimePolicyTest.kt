package com.sky22333.frpandroid.core.runtime

import com.sky22333.frpandroid.core.frp.FrpResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun retryDelayGrowsAndCapsAtTenMinutes() {
        assertEquals(10L, FrpRuntimePolicy.retryDelaySeconds(1))
        assertEquals(30L, FrpRuntimePolicy.retryDelaySeconds(2))
        assertEquals(600L, FrpRuntimePolicy.retryDelaySeconds(6))
        assertEquals(600L, FrpRuntimePolicy.retryDelaySeconds(100))
    }

    @Test
    fun screenOffKeepAliveRequiresEnabledScreenOffAndActiveInstance() {
        assertTrue(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(true, true, true))
        assertFalse(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(false, true, true))
        assertFalse(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(true, false, true))
        assertFalse(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(true, true, false))
    }

    @Test
    fun wifiLockRequiresScreenOffKeepAliveAndWifiDefaultNetwork() {
        assertTrue(FrpRuntimePolicy.shouldHoldWifiLock(keepAlive = true, defaultNetworkIsWifi = true))
        assertFalse(FrpRuntimePolicy.shouldHoldWifiLock(keepAlive = false, defaultNetworkIsWifi = true))
        assertFalse(FrpRuntimePolicy.shouldHoldWifiLock(keepAlive = true, defaultNetworkIsWifi = false))
    }
}
