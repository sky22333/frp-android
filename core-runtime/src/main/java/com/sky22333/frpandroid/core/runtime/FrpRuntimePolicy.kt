package com.sky22333.frpandroid.core.runtime

import com.sky22333.frpandroid.core.frp.FrpResult

internal object FrpRuntimePolicy {
    fun isStartSatisfied(result: FrpResult): Boolean =
        result.isSuccess || result.isAlreadyRunning

    fun shouldRetryStart(result: FrpResult, autoRetryEnabled: Boolean): Boolean =
        autoRetryEnabled &&
            !isStartSatisfied(result) &&
            !result.isInvalidToml &&
            !result.isInvalidTempDir &&
            !result.isTlsFileMissing

    fun shouldHoldScreenOffKeepAlive(enabled: Boolean, screenOff: Boolean, hasActiveInstances: Boolean): Boolean =
        enabled && screenOff && hasActiveInstances

    fun shouldHoldWifiLock(keepAlive: Boolean, defaultNetworkIsWifi: Boolean): Boolean =
        keepAlive && defaultNetworkIsWifi

    fun retryDelaySeconds(attempt: Int): Long =
        when (attempt.coerceAtLeast(1)) {
            1 -> 10
            2 -> 30
            3 -> 60
            4 -> 120
            5 -> 300
            else -> 600
        }
}
