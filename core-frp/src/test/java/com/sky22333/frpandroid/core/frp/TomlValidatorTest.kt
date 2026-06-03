package com.sky22333.frpandroid.core.frp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlValidatorTest {
    private val validator = TomlValidator()

    @Test
    fun validTomlPasses() {
        val result = validator.validate(
            """
            serverAddr = "127.0.0.1"
            serverPort = 7000

            [[proxies]]
            name = "nas-web"
            type = "tcp"
            localIP = "127.0.0.1"
            localPort = 8080
            remotePort = 8080
            """.trimIndent(),
        )

        assertTrue(result.isSuccess)
        assertNull(result.code)
    }

    @Test
    fun invalidTomlReturnsInvalidTomlCode() {
        val result = validator.validate("serverAddr = ")

        assertEquals("INVALID_TOML", result.code)
        assertTrue(result.message.isNotBlank())
    }
}
