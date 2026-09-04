package com.fentai.arcmenu.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class EditorProtocolTest {
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
}
