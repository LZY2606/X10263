package org.heapshadows.forensics.json

/**
 * Position-tracked JSON. Every value remembers its character offset plus
 * 1-based line/column so domain parsers can emit diagnostics that point at an
 * exact place inside an imported snapshot.
 */
data class JsonPos(val offset: Int, val line: Int, val col: Int) {
    override fun toString(): String = "line $line column $col (offset $offset)"
}

sealed interface JsonValue {
    val start: JsonPos
    val end: JsonPos
}

data class JsonObject(val members: List<Pair<String, JsonValue>>, override val start: JsonPos, override val end: JsonPos) :
    JsonValue {
    fun find(key: String): JsonValue? = members.firstOrNull { it.first == key }?.second
}

data class JsonArray(val elements: List<JsonValue>, override val start: JsonPos, override val end: JsonPos) : JsonValue
data class JsonString(val value: String, override val start: JsonPos, override val end: JsonPos) : JsonValue
data class JsonNumber(val text: String, override val start: JsonPos, override val end: JsonPos) : JsonValue {
    val asLongOrNull: Long? = text.toLongOrNull()
    val asDoubleOrNull: Double? = text.toDoubleOrNull()
}

data class JsonBoolean(val value: Boolean, override val start: JsonPos, override val end: JsonPos) : JsonValue
data class JsonNull(override val start: JsonPos, override val end: JsonPos) : JsonValue

class JsonParseException(message: String, val pos: JsonPos?) : RuntimeException(message)

private class Reader(val text: String) {
    var i = 0
    var line = 1
    var col = 1

    fun pos(): JsonPos = JsonPos(i, line, col)

    fun peek(): Char = if (i < text.length) text[i] else ' '

    fun next(): Char {
        val c = text[i]
        i++
        if (c == '\n') {
            line++; col = 1
        } else col++
        return c
    }

    fun skipWs() {
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') next() else break
        }
    }

    fun expect(c: Char) {
        if (i >= text.length || text[i] != c) throw JsonParseException("expected '$c' but found ${near()}", pos())
        next()
    }

    fun near(): String = if (i >= text.length) "end of input" else "'" + text[i] + "'"
}

object JsonParser {
    fun parse(text: String): JsonValue {
        val r = Reader(text)
        r.skipWs()
        val v = parseValue(r)
        r.skipWs()
        if (r.i != text.length) throw JsonParseException("trailing characters after JSON value", r.pos())
        return v
    }

    private fun parseValue(r: Reader): JsonValue {
        r.skipWs()
        if (r.i >= r.text.length) throw JsonParseException("unexpected end of input", r.pos())
        return when (r.peek()) {
            '{' -> parseObject(r)
            '[' -> parseArray(r)
            '"' -> parseStringNode(r)
            't', 'f' -> parseBool(r)
            'n' -> parseNull(r)
            else -> parseNumber(r)
        }
    }

    private fun parseObject(r: Reader): JsonValue {
        val start = r.pos(); r.expect('{'); r.skipWs()
        val members = mutableListOf<Pair<String, JsonValue>>()
        if (r.peek() == '}') { val end = r.pos(); r.next(); return JsonObject(members, start, end) }
        while (true) {
            r.skipWs()
            if (r.peek() != '"') throw JsonParseException("expected member name, found ${r.near()}", r.pos())
            val keyStart = r.pos()
            val key = parseString(r)
            r.skipWs(); r.expect(':'); r.skipWs()
            members += JsonString(key, keyStart, r.pos()).value to parseValue(r)
            r.skipWs()
            when (r.peek()) {
                ',' -> { r.next(); continue }
                '}' -> { val end = r.pos(); r.next(); return JsonObject(members, start, end) }
                else -> throw JsonParseException("expected ',' or '}', found ${r.near()}", r.pos())
            }
        }
    }

    private fun parseArray(r: Reader): JsonValue {
        val start = r.pos(); r.expect('['); r.skipWs()
        val elements = mutableListOf<JsonValue>()
        if (r.peek() == ']') { val end = r.pos(); r.next(); return JsonArray(elements, start, end) }
        while (true) {
            r.skipWs()
            elements += parseValue(r)
            r.skipWs()
            when (r.peek()) {
                ',' -> { r.next(); continue }
                ']' -> { val end = r.pos(); r.next(); return JsonArray(elements, start, end) }
                else -> throw JsonParseException("expected ',' or ']', found ${r.near()}", r.pos())
            }
        }
    }

    private fun parseStringNode(r: Reader): JsonValue {
        val start = r.pos()
        val s = parseString(r)
        return JsonString(s, start, r.pos())
    }

    private fun parseString(r: Reader): String {
        if (r.peek() != '"') throw JsonParseException("expected string", r.pos())
        r.next()
        val sb = StringBuilder()
        while (true) {
            if (r.i >= r.text.length) throw JsonParseException("unterminated string", r.pos())
            val c = r.next()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (r.i >= r.text.length) throw JsonParseException("unterminated escape", r.pos())
                    when (val e = r.next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append(Char(0x08))
                        'f' -> sb.append(Char(0x0C))
                        'n' -> sb.append(Char(0x0A))
                        'r' -> sb.append(Char(0x0D))
                        't' -> sb.append(Char(0x09))
                        'u' -> {
                            if (r.i + 4 > r.text.length) throw JsonParseException("bad unicode escape", r.pos())
                            val hex = r.text.substring(r.i, r.i + 4)
                            val cp = hex.toIntOrNull(16) ?: throw JsonParseException("bad unicode escape", r.pos())
                            repeat(4) { r.next() }
                            sb.append(cp.toChar())
                        }
                        else -> throw JsonParseException("bad escape", r.pos())
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseBool(r: Reader): JsonValue {
        val start = r.pos()
        return when {
            r.text.startsWith("true", r.i) -> {
                repeat(4) { r.next() }; JsonBoolean(true, start, r.pos())
            }
            r.text.startsWith("false", r.i) -> {
                repeat(5) { r.next() }; JsonBoolean(false, start, r.pos())
            }
            else -> throw JsonParseException("invalid token", r.pos())
        }
    }

    private fun parseNull(r: Reader): JsonValue {
        val start = r.pos()
        if (!r.text.startsWith("null", r.i)) throw JsonParseException("invalid token", start)
        repeat(4) { r.next() }
        return JsonNull(start, r.pos())
    }

    private fun parseNumber(r: Reader): JsonValue {
        val start = r.pos()
        val begin = r.i
        if (r.peek() == '-') r.next()
        if (r.i >= r.text.length || !r.peek().isDigit()) throw JsonParseException("invalid number", r.pos())
        while (r.i < r.text.length && r.peek().isDigit()) r.next()
        if (r.i < r.text.length && r.peek() == '.') {
            r.next()
            if (r.i >= r.text.length || !r.peek().isDigit()) throw JsonParseException("invalid fraction", r.pos())
            while (r.i < r.text.length && r.peek().isDigit()) r.next()
        }
        if (r.i < r.text.length && (r.peek() == 'e' || r.peek() == 'E')) {
            r.next()
            if (r.i < r.text.length && (r.peek() == '+' || r.peek() == '-')) r.next()
            if (r.i >= r.text.length || !r.peek().isDigit()) throw JsonParseException("invalid exponent", r.pos())
            while (r.i < r.text.length && r.peek().isDigit()) r.next()
        }
        return JsonNumber(r.text.substring(begin, r.i), start, r.pos())
    }
}

/**
 * Canonical serialization: object keys sorted lexicographically, no whitespace.
 * Used for content hashing so equivalent inputs always hash identically.
 */
object CanonicalJson {
    fun write(v: JsonValue): String {
        val sb = StringBuilder()
        writeInto(sb, v)
        return sb.toString()
    }

    private fun writeInto(sb: StringBuilder, v: JsonValue) {
        when (v) {
            is JsonObject -> {
                sb.append('{')
                v.members.sortedBy { it.first }.forEachIndexed { idx, (k, child) ->
                    if (idx > 0) sb.append(',')
                    writeString(sb, k); sb.append(':'); writeInto(sb, child)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                v.elements.forEachIndexed { idx, child ->
                    if (idx > 0) sb.append(',')
                    writeInto(sb, child)
                }
                sb.append(']')
            }
            is JsonString -> writeString(sb, v.value)
            is JsonNumber -> sb.append(normalizeNumber(v.text))
            is JsonBoolean -> sb.append(if (v.value) "true" else "false")
            is JsonNull -> sb.append("null")
        }
    }

    fun normalizeNumber(text: String): String {
        val d = text.toDoubleOrNull() ?: return text
        if (text.contains('e', true) || text.contains('.')) {
            if (d.isFinite() && d % 1.0 == 0.0 && d in -9.0e15..9.0e15) return d.toLong().toString()
        }
        return text
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            Char(0x0A) -> sb.append("\\n")
            Char(0x0D) -> sb.append("\\r")
            Char(0x09) -> sb.append("\\t")
            Char(0x08) -> sb.append("\\b")
            Char(0x0C) -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
