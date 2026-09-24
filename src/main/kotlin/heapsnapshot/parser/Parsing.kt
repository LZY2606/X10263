package heapsnapshot.parser

import heapsnapshot.domain.*
import kotlinx.serialization.json.*

class DiagnosticCollector(private val cap: Long) {
    private val items = mutableListOf<ParseDiagnostic>()
    fun add(d: ParseDiagnostic) { if (items.size.toLong() < cap) items += d }
    fun error(code: String, message: String, location: String, raw: String? = null) =
        add(ParseDiagnostic(Severity.ERROR, code, message, location, raw))
    fun warn(code: String, message: String, location: String, raw: String? = null) =
        add(ParseDiagnostic(Severity.WARNING, code, message, location, raw))
    fun info(code: String, message: String, location: String, raw: String? = null) =
        add(ParseDiagnostic(Severity.INFO, code, message, location, raw))
    fun all(): List<ParseDiagnostic> = items
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }
internal fun JsonElement?.rawStr(): String? = (this as? JsonPrimitive)?.content
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.content?.toLongOrNull()
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** 解析带单位的大小。原始表示保留；无法转换时就地诊断，不丢值。 */
internal fun parseSize(rawEl: JsonElement?, location: String, diags: DiagnosticCollector, defaultUnit: String): Quantity {
    when (rawEl) {
        is JsonObject -> {
            val raw = rawEl["raw"]?.rawStr() ?: rawEl.toString()
            val value = rawEl["value"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            val unit = rawEl["unit"]?.rawStr()
            return when {
                value != null && unit != null -> Quantity(raw, value, unit)
                value != null -> Quantity(raw, value, defaultUnit)
                else -> {
                    diags.error("UNCONVERTIBLE_QUANTITY", "大小无法解析为数值+单位: $raw", "$location.size", raw)
                    Quantity.unconvertible(raw, "大小无法解析为数值+单位", "$location.size")
                }
            }
        }
        is JsonPrimitive -> {
            val raw = rawEl.content
            val match = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*([A-Za-z]+)$").find(raw.trim())
            if (match != null) {
                return Quantity(raw, match.groupValues[1].toDouble(), match.groupValues[2])
            }
            val num = raw.trim().toDoubleOrNull()
            return if (num != null) Quantity(raw, num, defaultUnit)
            else {
                diags.error("UNCONVERTIBLE_QUANTITY", "大小无法解析: $raw", "$location.size", raw)
                Quantity.unconvertible(raw, "大小无法解析", "$location.size")
            }
        }
        else -> {
            diags.error("MISSING_SIZE", "缺少大小字段", location, null)
            return Quantity.unconvertible("<missing>", "缺少大小字段", location)
        }
    }
}

/** 解析地址：保留原始串与进制基准；地址不是稳定身份。 */
internal fun parseAddress(rawEl: JsonElement?, location: String, diags: DiagnosticCollector): AddressValue {
    val raw = rawEl?.rawStr()
    if (raw == null) {
        diags.error("MISSING_ADDRESS", "缺少地址字段", location, null)
        return AddressValue("<missing>", null, "hex",
            ParseDiagnostic(Severity.ERROR, "MISSING_ADDRESS", "缺少地址字段", location, null))
    }
    val hex = Regex("^(?:0x|0X)?([0-9a-fA-F]+)$").find(raw.trim())
    val dec = raw.trim().toLongOrNull()
    return when {
        hex != null -> AddressValue(raw, hex.groupValues[1].toLong(16), "hex")
        dec != null -> AddressValue(raw, dec, "dec")
        else -> AddressValue(raw, null, "hex",
            ParseDiagnostic(Severity.WARNING, "UNCONVERTIBLE_ADDRESS", "地址无法规范化: $raw", location, raw))
    }
}
