package com.fentai.arcmenu.paper.pack

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

/** GIF previews use the composed logical canvas rather than the first partial frame. */
internal object ArcPackImages {
    fun dimensions(input: ImageInputStream?): Pair<Double, Double>? {
        if (input == null) return null
        val header = ByteArray(10)
        val count = input.read(header)
        input.seek(0)
        if (count == header.size && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) {
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val width = bytes.getShort(6).toInt() and 65535
            val height = bytes.getShort(8).toInt() and 65535
            return if (width > 0 && height > 0) width.toDouble() to height.toDouble() else null
        }
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            return reader.getWidth(0).toDouble() to reader.getHeight(0).toDouble()
        } finally { reader.dispose() }
    }
}
