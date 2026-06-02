package com.sky22333.frpandroid.core.runtime

import com.sky22333.frpandroid.core.frp.FrpResult

internal object FrpRuntimePolicy {
    fun isStartSatisfied(result: FrpResult): Boolean =
        result.isSuccess || result.isAlreadyRunning

    fun shouldRetryStart(result: FrpResult, autoRetryEnabled: Boolean): Boolean =
        autoRetryEnabled &&
            !isStartSatisfied(result) &&
            !result.isInvalidToml &&
            !result.isInvalidTempDir
}
