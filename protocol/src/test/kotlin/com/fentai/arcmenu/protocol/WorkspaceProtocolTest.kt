package com.fentai.arcmenu.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkspaceProtocolTest {
    @Test fun `independent packets preserve request identity and Unicode`() {
        val request = WorkspaceProtocol.Request(91, "export", "{\"title\":\"界面包\",\"projects\":[]}")
        assertEquals(request, WorkspaceProtocol.decode(WorkspaceProtocol.encode(request)))
        val reply = WorkspaceProtocol.Reply(91, false, "export", "{}", "A project is open")
        assertEquals(reply, WorkspaceProtocol.decode(WorkspaceProtocol.encode(reply)))
    }
    @Test fun `workspace responses use existing bounded frame transport`() {
        val reply = WorkspaceProtocol.Reply(92, true, "list", "x".repeat(100_000), "")
        val frames = EditorProtocol.frame(13, WorkspaceProtocol.encode(reply)).map(EditorProtocol::decodeFrame)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.messageId() == 13 })
        assertEquals(reply, WorkspaceProtocol.decode(frames.flatMap { it.data().toList() }.toByteArray()))
    }
    @Test fun `invalid packets and UTF8 cannot be interpreted as actions`() {
        val bytes = WorkspaceProtocol.encode(WorkspaceProtocol.Request(1, "list", "{}"))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceProtocol.decode(bytes + byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceProtocol.decode(bytes.copyOf(7)) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceProtocol.decode(EditorProtocol.encode(EditorProtocol.HelloPacket("old"))) }
        val malformed = bytes.clone().also { it[it.size - 2] = 0xc0.toByte() }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceProtocol.decode(malformed) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceProtocol.encode(WorkspaceProtocol.Request(1, "list", "x".repeat(26_001))) }
    }
}
