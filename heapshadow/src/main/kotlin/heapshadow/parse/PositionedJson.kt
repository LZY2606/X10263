package heapshadow.parse

import heapshadow.domain.Diagnostic

/**
 * Minimal position-aware JSON parser. It exists specifically so that format corruption and
 * unconvertible values can be reported with line/offset/json-pointer diagnostics instead of a
 * generic exception. The token DOM also preserves every original element for the raw layer.
 */
sealed class JsonNode {
    abstract val startOffset: Long
    abstract val line: Int
}

data class JsonObj(val members: List<Pair<String, JsonNode>>, override val startOffset: Long, override val line: Int) : JsonNode() {
    fun get(key: String): JsonNode? = members.firstOrNull { it.first == key }?.second
    fun keyLine(key: String): Int = members.firstOrNull { it.first == key }?.second?.line ?: line
    fun keyOffset(key: String): Long = members.firstOrNull { it.first == key }?.second?.startOffset ?: startOffset
}

data class JsonArr(val items: List<JsonNode>, override val startOffset: Long, override val line: Int) : JsonNode()

data class JsonStr(val value: String, override val startOffset: Long, override val line: Int) : JsonNode()
data class JsonNum(val raw: String, val value: Double, override val startOffset: Long, override val line: Int) : JsonNode()
data class JsonBool(val value: Boolean, override val startOffset: Long, override val line: Int) : JsonNode()
data class JsonNullNode(override val startOffset: Long, override val line: Int) : JsonNode()

class JsonParseException(val diagnostic: Diagnostic) : RuntimeException(diagnostic.message)

object PositionedJsonParser {

    fun parse(text: String): JsonNode {
        val p = Cursor(text)
        p.skipWs()
        val node = p.readValue("/")
        p.skipWs()
        if (!p.eof()) throw p.error("FORMAT_TRAILING", "Trailing content after JSON document at offset ${p.pos}")
        return node
    }

    private class Cursor(val text: String) {
        var pos = 0
        var line = 1

        fun eof() = pos >= text.length

        fun error(code: String, message: String, pointer: String? = null): JsonParseException {
            val atLine = if (pos < text.length) line else line
            JsonParseException(
                Diagnostic(code, "error", message, atLine, pos.toLong(), pointer)
            )
            return JsonParseException(Diagnostic(code, "error", message, atLine, pos.toLong(), pointer))
        }

        fun skipWs() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\r' -> pos++
                    '\n' -> { pos++; line++ }
                    else -> return
                }
            }
        }

        fun readValue(pointer: String): JsonNode {
            skipWs()
            if (eof()) throw error("FORMAT_EOF", "Unexpected end of document", pointer)
            return when (text[pos]) {
                '{' -> readObject(pointer)
                '[' -> readArray(pointer)
                '"' -> JsonStr(readString(), mark().first, mark().second)
                't', 'f' -> readBool(pointer)
                'n' -> readNull(pointer)
                else -> readNumber(pointer)
            }
        }

        private fun mark(): Pair<Long, Int> = pos.toLong() to line

        private fun expect(literal: String, code: String, pointer: String) {
            if (!text.startsWith(literal, pos)) throw error(code, "Expected '$literal'", pointer)
            for (ch in literal) {
                if (ch == '\n') line++
                pos++
            }
        }

        private fun readBool(pointer: String): JsonBool {
            val (off, ln) = mark()
            if (text.startsWith("true", pos)) { pos += 4; return JsonBool(true, off, ln) }
            if (text.startsWith("false", pos)) { pos += 5; return JsonBool(false, off, ln) }
            throw error("FORMAT_LITERAL", "Invalid literal (expected true/false)", pointer)
        }

        private fun readNull(pointer: String): JsonNullNode {
            val (off, ln) = mark()
            expect("null", "FORMAT_LITERAL", pointer)
            return JsonNullNode(off, ln)
        }

        private fun readNumber(pointer: String): JsonNum {
            val (off, ln) = mark()
            val start = pos
            if (text[pos] == '-') pos++
            var sawDigit = false
            while (pos < text.length && text[pos].isDigit()) { pos++; sawDigit = true }
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) { pos++; sawDigit = true }
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (!sawDigit) throw error("FORMAT_NUMBER", "Invalid number token", pointer)
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull()
                ?: throw error("FORMAT_NUMBER", "Number out of representable range: '$raw'", pointer)
            return JsonNum(raw, value, off, ln)
        }

        fun readString(): String {
            val startLine = line
            val startPos = pos
            require(pos < text.length && text[pos] == '"')
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos]
                when (ch) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (pos >= text.length) throw error("FORMAT_ESCAPE", "Unterminated escape", null)
                        when (text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) throw error("FORMAT_ESCAPE", "Bad unicode escape", null)
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException(Diagnostic("FORMAT_ESCAPE", "error", "Bad unicode escape '\\u$hex'", startLine, startPos.toLong()))
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw error("FORMAT_ESCAPE", "Invalid escape '\\${text[pos]}'", null)
                        }
                        pos++
                    }
                    '\n' -> throw error("FORMAT_STRING", "Unterminated string (raw newline)", null)
                    else -> { sb.append(ch); pos++ }
                }
            }
            throw error("FORMAT_STRING", "Unterminated string", null)
        }

        private fun readObject(pointer: String): JsonObj {
            val (off, ln) = mark()
            pos++ // {
            val members = mutableListOf<Pair<String, JsonNode>>()
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return JsonObj(emptyList(), off, ln) }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') throw error("FORMAT_OBJECT", "Expected string key in object", pointer)
                val keyLine = line
                val key = readString()
                if (members.any { it.first == key }) {
                    throw JsonParseException(
                        Diagnostic("FORMAT_DUPLICATE_KEY", "error", "Duplicate object key '$key'", keyLine, (pos).toLong(), "$pointer/$key")
                    )
                }
                skipWs()
                if (pos >= text.length || text[pos] != ':') throw error("FORMAT_OBJECT", "Expected ':' after key '$key'", "$pointer/$key")
                pos++
                val childPointer = "$pointer/$key"
                val value = readValue(childPointer)
                members.add(key to value)
                skipWs()
                when {
                    pos < text.length && text[pos] == ',' -> { pos++; continue }
                    pos < text.length && text[pos] == '}' -> { pos++; return JsonObj(members, off, ln) }
                    else -> throw error("FORMAT_OBJECT", "Expected ',' or '}' in object", pointer)
                }
            }
        }

        private fun readArray(pointer: String): JsonArr {
            val (off, ln) = mark()
            pos++ // [
            val items = mutableListOf<JsonNode>()
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return JsonArr(emptyList(), off, ln) }
            var idx = 0
            while (true) {
                val value = readValue("$pointer/$idx")
                items.add(value)
                idx++
                skipWs()
                when {
                    pos < text.length && text[pos] == ',' -> { pos++; continue }
                    pos < text.length && text[pos] == ']' -> { pos++; return JsonArr(items, off, ln) }
                    else -> throw error("FORMAT_ARRAY", "Expected ',' or ']' in array", pointer)
                }
            }
        }
    }
}
