package com.sky22333.frpandroid.core.frp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpRuntimeManagerTest {
    private val manager = FrpRuntimeManager()

    @Test
    fun parsesClientInstanceLine() {
        val state = manager.parseInstanceLine("client:nas-web:running")

        requireNotNull(state)
        assertEquals("nas-web", state.id)
        assertEquals(FrpType.Client, state.type)
        assertEquals(FrpInstanceStatus.Running, state.state)
        assertNull(state.lastError)
    }

    @Test
    fun parsesServerInstanceLineWithError() {
        val state = manager.parseInstanceLine("server:edge:failed:START_FAILED: bind failed")

        requireNotNull(state)
        assertEquals("edge", state.id)
        assertEquals(FrpType.Server, state.type)
        assertEquals(FrpInstanceStatus.Failed, state.state)
        assertEquals("START_FAILED: bind failed", state.lastError)
    }

    @Test
    fun parsesStoppingInstanceLine() {
        val state = manager.parseInstanceLine("server:edge:stopping")

        requireNotNull(state)
        assertEquals("edge", state.id)
        assertEquals(FrpType.Server, state.type)
        assertEquals(FrpInstanceStatus.Stopping, state.state)
        assertNull(state.lastError)
    }

    @Test
    fun ignoresUnknownLine() {
        assertNull(manager.parseInstanceLine("bad-data"))
        assertNull(manager.parseInstanceLine("proxy:id:running"))
    }

    @Test
    fun rejectsPartiallyUnparseableInstanceList() {
        val result = manager.parseInstances("client:nas-web:running\nbad-data")

        assertTrue(result is FrpRuntimeQueryResult.Failure)
    }

    @Test
    fun extractsTlsFilePathsFromToml() {
        val paths = manager.tlsFilePaths(
            """
            transport.tls.trustedCaFile = "/files/ca.pem"
            transport.tls.certFile = "/files/cert.pem"
            transport.tls.keyFile = "/files/key.pem"
            """.trimIndent(),
        )

        assertEquals(listOf("/files/ca.pem", "/files/cert.pem", "/files/key.pem"), paths)
    }
}
