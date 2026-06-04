package com.sky22333.frpandroid.core.data

data class TlsFileInfo(
    val role: TlsFileRole,
    val name: String,
    val path: String,
)

enum class TlsFileRole(val fileName: String) {
    TrustedCa("ca.pem"),
    Certificate("cert.pem"),
    PrivateKey("key.pem"),
}
