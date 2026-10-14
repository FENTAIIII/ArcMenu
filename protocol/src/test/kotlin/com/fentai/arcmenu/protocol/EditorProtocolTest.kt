package com.fentai.arcmenu.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class EditorProtocolTest {
    @Test fun `old protocol is identifiable for a vanilla chat upgrade message`() {
        val old = EditorProtocol.encode(EditorProtocol.HelloPacket("0.1.0-M3.3"))
        old[4] = 9
        assertThrows(EditorProtocol.ProtocolMismatchException::class.java) { EditorProtocol.decode(old) }
        assertFalse(EditorProtocol.supportsClient("0.1.0-M3.3"))
        assertFalse(EditorProtocol.supportsClient(null))
        assertTrue(EditorProtocol.supportsClient("1.0.0"))
        assertTrue(EditorProtocol.supportsClient("1.0.1"))
        assertTrue(EditorProtocol.supportsClient("1.1.0"))
        assertTrue(EditorProtocol.supportsClient("1.2.0"))
        assertTrue(EditorProtocol.supportsClient("1.2.1"))
        assertTrue(EditorProtocol.supportsClient("3.0.0"))
        assertTrue(EditorProtocol.supportsClient("3.0.1"))
        assertEquals("3.0.1", EditorProtocol.EDITOR_VERSION)
        assertEquals(10, EditorProtocol.VERSION)
    }

    private val pointer = EditorProtocol.Pointer(
        640.0, 360.0, 160.0, 90.0, 960.0, 540.0, 0.0, 0.0,
    )

    @Test
    fun `every packet round trips without losing semantics`() {
        val nodes = listOf(EditorProtocol.NodeSnapshot(
            "card", "group", EditorProtocol.KIND_RECTANGLE,
            12.5, -8.25, 110.0, 55.0, 15.0, true, false,
            listOf(EditorProtocol.PropertySnapshot("color", EditorProtocol.PROPERTY_COLOR, "#123456")),
        ))
        val packets: List<EditorProtocol.Packet> = listOf(
            EditorProtocol.HelloPacket("0.3.0"),
            EditorProtocol.MovePacket(4, EditorProtocol.TAB_FRONTEND, "card", pointer, 2.5, -1.5, 91, false),
            EditorProtocol.ResizePacket(5, EditorProtocol.TAB_FRONTEND, "card", pointer, 92, true),
            EditorProtocol.SwitchTabPacket(EditorProtocol.TAB_BACKEND),
            EditorProtocol.UndoPacket(6), EditorProtocol.RedoPacket(7),
            EditorProtocol.SavePacket(8), EditorProtocol.ApplyPacket(8),
            EditorProtocol.ClosePacket(),
            EditorProtocol.ProbePacket(8, EditorProtocol.TAB_BACKEND, pointer),
            EditorProtocol.CreatePacket(8, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_IMAGE, "group", "/ui/card.png"),
            EditorProtocol.CreatePacket(8, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_GIF, "group", "/ui/spinner.gif"),
            EditorProtocol.DeletePacket(8, EditorProtocol.TAB_FRONTEND, "text"),
            EditorProtocol.DeleteManyPacket(8, EditorProtocol.TAB_FRONTEND, listOf("text", "image")),
            EditorProtocol.DuplicatePacket(8, EditorProtocol.TAB_FRONTEND, listOf("card", "text"), "group", false),
            EditorProtocol.GroupPacket(8, listOf("card", "text")),
            EditorProtocol.ReorderPacket(8, EditorProtocol.TAB_FRONTEND, listOf("card", "text"), "image"),
            EditorProtocol.ReparentPacket(8, listOf("card", "text"), "group", "image"),
            EditorProtocol.SetPropertyPacket(8, EditorProtocol.TAB_FRONTEND, "card", "offset.x", "12.5", 93, false),
            EditorProtocol.SaveTemplatePacket(8, "card", "saved-card"),
            EditorProtocol.InstantiateTemplatePacket(8, "saved-card"),
            EditorProtocol.DeleteTemplatePacket(8, "saved-card"),
            EditorProtocol.SnapshotPacket(8, "example", 320.0, 180.0, true, false,
                "26.1.2", nodes, emptyList(),
                listOf(EditorProtocol.ImageSnapshot("/example.png", 64, 32)),
                listOf(EditorProtocol.TemplateSnapshot("saved-card", "card", 3))),
            EditorProtocol.SnapshotPacket(8, "gif", 320.0, 180.0, true, false, "26.3",
                listOf(EditorProtocol.NodeSnapshot("animation", "", EditorProtocol.KIND_GIF, 0.0, 0.0, 32.0, 32.0, 0.0, true, false,
                    listOf(EditorProtocol.PropertySnapshot("source", EditorProtocol.PROPERTY_TEXT, "/ui/spinner.gif"),
                        EditorProtocol.PropertySnapshot("speed", EditorProtocol.PROPERTY_NUMBER, "0.5")))), emptyList(),
                listOf(EditorProtocol.ImageSnapshot("/ui/spinner.gif", 32, 32)), emptyList()),
            EditorProtocol.AckPacket(EditorProtocol.OP_MOVE, 9, "card",
                20.0, 30.0, 110.0, 55.0, 20.0, 30.0, "card-area", true, false, "ok"),
            EditorProtocol.ErrorPacket(9, "revision conflict"),
        )

        packets.forEach { packet -> assertEquals(packet, EditorProtocol.decode(EditorProtocol.encode(packet))) }
    }

    @Test
    fun `large snapshot frames reassemble exactly`() {
        val nodes = (0 until 2_000).map { index ->
            EditorProtocol.NodeSnapshot(
                "node-$index", "", EditorProtocol.KIND_TEXT,
                index.toDouble(), -index.toDouble(), 20.0, 10.0, 0.0, true, false,
            )
        }
        val packet = EditorProtocol.encode(EditorProtocol.SnapshotPacket(
            31, "large", 320.0, 180.0, true, false, "26.1.2", nodes, emptyList(),
        ))
        val encodedFrames = EditorProtocol.frame(77, packet)
        assertTrue(encodedFrames.size > 1)

        val output = ByteArrayOutputStream()
        encodedFrames.forEachIndexed { index, encoded ->
            val frame = EditorProtocol.decodeFrame(encoded)
            assertEquals(77, frame.messageId())
            assertEquals(index, frame.index())
            assertEquals(encodedFrames.size, frame.count())
            output.write(frame.data())
        }
        assertArrayEquals(packet, output.toByteArray())
    }

    @Test
    fun `malformed and truncated packets are rejected`() {
        val hello = EditorProtocol.encode(EditorProtocol.HelloPacket("client"))
        assertThrows(IllegalArgumentException::class.java) {
            EditorProtocol.decode(hello.copyOf(hello.size - 1))
        }
        val frame = EditorProtocol.frame(1, hello).first()
        assertThrows(IllegalArgumentException::class.java) {
            EditorProtocol.decodeFrame(frame.copyOf(frame.size - 1))
        }
    }

    @Test fun `UTF8 string boundary and oversized property identify the responsible field`() {
        val value = "中".repeat(EditorProtocol.MAX_STRING_BYTES / 3) + "a"
        val packet = EditorProtocol.SetPropertyPacket(7, EditorProtocol.TAB_BACKEND, "buy", "actions", value)
        assertEquals(EditorProtocol.MAX_STRING_BYTES, value.toByteArray(Charsets.UTF_8).size)
        assertEquals(packet, EditorProtocol.decode(EditorProtocol.encode(packet)))
        val failure = assertThrows(EditorProtocol.StringLimitException::class.java) {
            EditorProtocol.encode(packet.copyValue(value + "a"))
        }
        assertEquals("backend.buy.actions", failure.field())
        assertEquals(EditorProtocol.MAX_STRING_BYTES + 1, failure.byteCount())
        assertTrue(failure.message!!.contains("UTF-8 bytes"))
        val node = EditorProtocol.NodeSnapshot("buy", "", EditorProtocol.KIND_REGION, 0.0, 0.0, 1.0, 1.0,
            0.0, true, false, listOf(EditorProtocol.PropertySnapshot("actions", EditorProtocol.PROPERTY_MULTILINE, value + "a")))
        val snapshot = EditorProtocol.SnapshotPacket(7, "shop", 320.0, 180.0, false, true, "26.3", emptyList(), listOf(node))
        assertEquals("backend.buy.actions", assertThrows(EditorProtocol.StringLimitException::class.java) {
            EditorProtocol.encode(snapshot)
        }.field())
    }

    private fun EditorProtocol.SetPropertyPacket.copyValue(value: String) =
        EditorProtocol.SetPropertyPacket(revision(), tab(), nodeId(), key(), value, gestureId(), finalUpdate())

    @Test fun `large Chinese edit assembles both directions with reordered and repeated frames`() {
        val packet = EditorProtocol.SetPropertyPacket(3, EditorProtocol.TAB_BACKEND, "buy", "actions", "right: 'tell: " + "中文".repeat(13_000) + "'")
        val frames = EditorProtocol.frame(42, EditorProtocol.encode(packet))
        assertTrue(frames.size > 1)
        val assembler = EditorProtocol.FrameAssembler()
        assertNull(assembler.accept(frames.last()))
        assertNull(assembler.accept(frames.last()))
        for (frame in frames.dropLast(1).drop(1)) assertNull(assembler.accept(frame))
        val assembled = assembler.accept(frames.first())
        assertEquals(packet, EditorProtocol.decode(assembled))
        val stale = frames.first().clone().also { it[4] = 9 }
        assertThrows(EditorProtocol.ProtocolMismatchException::class.java) { assembler.accept(stale) }
    }

    @Test fun `negative and oversized incoming byte lengths are rejected`() {
        val hello = EditorProtocol.encode(EditorProtocol.HelloPacket("ok"))
        for (length in listOf(-1, EditorProtocol.MAX_STRING_BYTES + 1)) {
            val corrupted = hello.clone()
            ByteBuffer.wrap(corrupted).putInt(6, length)
            assertThrows(IllegalArgumentException::class.java) { EditorProtocol.decode(corrupted) }
        }
    }

    @Test fun `packet cap remains independent of individual string cap`() {
        val nodes = (0..4).map { index ->
            EditorProtocol.NodeSnapshot("node-$index", "", EditorProtocol.KIND_TEXT, 0.0, 0.0, 10.0, 10.0,
                0.0, true, false, listOf(EditorProtocol.PropertySnapshot("content", EditorProtocol.PROPERTY_TEXT, "a".repeat(240_000))))
        }
        val snapshot = EditorProtocol.SnapshotPacket(1, "large", 320.0, 180.0, false, true, "26.3", nodes, emptyList())
        val failure = assertThrows(EditorProtocol.PacketLimitException::class.java) { EditorProtocol.encode(snapshot) }
        assertTrue(failure.byteCount() > EditorProtocol.MAX_PACKET_BYTES)
        assertTrue(failure.message!!.contains(EditorProtocol.MAX_PACKET_BYTES.toString()))
    }
}
