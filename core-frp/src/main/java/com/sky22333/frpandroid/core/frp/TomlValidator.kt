package com.sky22333.frpandroid.core.frp

import org.tomlj.Toml

class TomlValidator {
    fun validate(toml: String): FrpResult {
        val result = Toml.parse(toml)
        if (!result.hasErrors()) return FrpResult(code = null, message = "")
        val message = result.errors().joinToString(separator = "\n") { error ->
            error.toString()
        }
        return FrpResult(code = "INVALID_TOML", message = message)
    }

    fun tlsFilePaths(toml: String): List<String> {
        val result = Toml.parse(toml)
        if (result.hasErrors()) return emptyList()
        return listOf(
            "transport.tls.trustedCaFile",
            "transport.tls.certFile",
            "transport.tls.keyFile",
        ).mapNotNull { key -> result.getString(key)?.ifBlank { null } }
    }
}
