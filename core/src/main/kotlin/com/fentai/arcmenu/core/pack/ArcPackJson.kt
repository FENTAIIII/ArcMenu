package com.fentai.arcmenu.core.pack

/** Small strict JSON codec for the package manifest and ownership journal. */
object ArcPackJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
                '\u000c' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> require(k is String); encode(k) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> throw IllegalArgumentException("Unsupported package JSON value")
    }

    fun decode(source: String): Any? = Reader(source).read()
    private class Reader(val source: String) {
        var offset = 0
        fun fail(): Nothing = throw IllegalArgumentException("Invalid package JSON at offset $offset")
        fun space() { while (offset < source.length && source[offset] in " \t\r\n") offset++ }
        fun take(c: Char): Boolean { space(); if (offset < source.length && source[offset] == c) { offset++; return true }; return false }
        fun read(): Any? { val result = value(0); space(); if (offset != source.length) fail(); return result }
        fun value(depth: Int): Any? {
            require(depth <= 32) { "Package JSON is too deeply nested" }; space()
            if (offset >= source.length) fail()
            return when (source[offset]) {
                '"' -> string()
                '{' -> {
                    offset++; val result = linkedMapOf<String, Any?>()
                    if (!take('}')) {
                        do {
                            space(); if (offset >= source.length || source[offset] != '"') fail()
                            val key = string(); if (!take(':') || result.containsKey(key)) fail()
                            result[key] = value(depth + 1)
                        } while (take(','))
                        if (!take('}')) fail()
                    }
                    result
                }
                '[' -> {
                    offset++; val result = mutableListOf<Any?>()
                    if (!take(']')) {
                        do { result += value(depth + 1) } while (take(','))
                        if (!take(']')) fail()
                    }
                    result
                }
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = offset
                    if (source[offset] == '-') offset++
                    if (source.getOrNull(offset) == '0') offset++ else {
                        if (source.getOrNull(offset) !in '1'..'9') fail()
                        while (source.getOrNull(offset) in '0'..'9') offset++
                    }
                    source.substring(start, offset).toLongOrNull() ?: fail()
                }
            }
        }
        fun literal(text: String, value: Any?): Any? { if (!source.startsWith(text, offset)) fail(); offset += text.length; return value }
        fun string(): String {
            offset++; val result = StringBuilder()
            while (offset < source.length) {
                val c = source[offset++]
                if (c == '"') return result.toString()
                if (c < ' ') fail()
                if (c != '\\') { result.append(c); continue }
                if (offset >= source.length) fail()
                result.append(when (source[offset++]) {
                    '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { if (offset + 4 > source.length) fail(); val n = source.substring(offset, offset + 4).toIntOrNull(16) ?: fail(); offset += 4; n.toChar() }
                    else -> fail()
                })
            }
            fail()
        }
    }
}
