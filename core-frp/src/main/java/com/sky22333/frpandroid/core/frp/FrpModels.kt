package com.sky22333.frpandroid.core.frp

enum class FrpType {
    Client,
    Server,
}

enum class FrpInstanceStatus {
    Stopped,
    Stopping,
    Running,
    Failed,
}

data class FrpProfile(
    val id: String,
    val name: String,
    val type: FrpType,
    val toml: String,
    val autoStart: Boolean,
    val updatedAt: Long,
)

data class FrpRuntimeState(
    val id: String,
    val type: FrpType,
    val state: FrpInstanceStatus,
    val lastError: String?,
)

sealed interface FrpRuntimeQueryResult {
    data class Success(val states: List<FrpRuntimeState>) : FrpRuntimeQueryResult
    data class Failure(val message: String) : FrpRuntimeQueryResult
}

data class FrpLog(
    val instanceId: String,
    val type: String,
    val level: String,
    val message: String,
    val time: Long,
    val uid: Long = 0,
)

data class FrpResult(
    val code: String?,
    val message: String,
) {
    val isSuccess: Boolean = code == null
    val isAlreadyRunning: Boolean = code == "ALREADY_RUNNING"
    val isInvalidTempDir: Boolean = code == "INVALID_TEMP_DIR"
    val isInvalidToml: Boolean = code == "INVALID_TOML"
    val isAlreadyStopped: Boolean =
        code == "STOP_FAILED" &&
            message.contains(Regex("(?i)(not running|already stopped|not found|no such instance)"))

    companion object {
        fun fromRaw(raw: String?): FrpResult {
            val value = raw.orEmpty()
            if (value.isBlank()) return FrpResult(code = null, message = "")
            val code = value.substringBefore(":", missingDelimiterValue = value).trim()
            return FrpResult(code = code.ifBlank { "UNKNOWN" }, message = value)
        }
    }
}

enum class ThemeMode {
    System,
    Light,
    Dark,
    Amoled,
}

enum class LanguageMode {
    System,
    Chinese,
    English,
}
