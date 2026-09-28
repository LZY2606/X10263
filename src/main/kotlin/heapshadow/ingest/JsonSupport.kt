package heapshadow.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
internal fun JsonObject.boolOr(key: String, default: Boolean = true): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

private val HEX = Regex("^(0x|#)?[0-9a-fA-F]+$")
private val DEC = Regex("^[0-9]+$")

fun parseAddressToLong(raw: String?): Long? {
    if (raw == null) return null
    val t = raw.trim()
    return when {
        t.lowercase().startsWith("0x") -> t.substring(2).toLongOrNull(16)
        t.startsWith("#") -> t.substring(1).toLongOrNull(16)
        HEX.matches(t) && (t.contains(Regex("[a-fA-F]"))) -> t.toLongOrNull(16)
        DEC.matches(t) -> t.toLongOrNull()
        else -> null
    }
}

/** SHA-256 十六进制摘要，输入为 UTF-8 字节。 */
fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * 规范化 JSON：对象键排序、紧凑输出，作为内容寻址/哈希链的稳定序列化。
 * JsonPrimitive 规范化保留原始标量文本（数字 001 与 1 视为不同输入是可接受的，
 * 因为规范化针对的是结构，原始表示仍保留在原始层）。
 */
fun canonicalJson(element: JsonElement): String = Json {
    prettyPrint = false
    encodeDefaults = true
}.encodeToString(JsonElement.serializer(), canonicalize(element))

fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (k, v) -> k to canonicalize(v) })
    is JsonArray -> JsonArray(element.map { canonicalize(it) })
    is JsonPrimitive -> element
}
