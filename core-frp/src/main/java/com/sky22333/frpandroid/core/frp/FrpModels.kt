package com.sky22333.frpandroid.core.frp

enum class FrpType {
    Client,
    Server,
}

enum class FrpInstanceStatus {
    Stopped,
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

data class FrpLog(
    val instanceId: String,
    val type: String,
    val level: String,
    val message: String,
    val time: Long,
)

data class FrpResult(
    val code: String?,
    val message: String,
) {
    val isSuccess: Boolean = code == null

    companion object {
        fun fromRaw(raw: String?): FrpResult {
            val value = raw.orEmpty()
            if (value.isBlank()) return FrpResult(code = null, message = "")
            val code = value.substringBefore(":", missingDelimiterValue = value).trim()
            return FrpResult(code = code.ifBlank { "UNKNOWN" }, message = value)
        }
    }
}
