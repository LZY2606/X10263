package heapshadow.ingest

import heapshadow.model.Diagnostic
import heapshadow.model.Quantity
import heapshadow.model.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val SIZE_UNIT_TO_BYTES: Map<String, Long> = mapOf(
    "b" to 1L,
    "byte" to 1L,
    "bytes" to 1L,
    "kb" to 1024L,
    "kib" to 1024L,
    "mb" to 1024L * 1024L,
    "mib" to 1024L * 1024L,
    "gb" to 1024L * 1024L * 1024L,
)

private val OFFSET_UNIT: Map<String, Long> = mapOf(
    "b" to 1L,
    "byte" to 1L,
    "bytes" to 1L,
    "line" to -1L,
    "lines" to -1L,
)

/**
 * 解析带单位的量。接受两种源表示：
 *  - 数字 + 可选 unit 字段（如 {"value": 24, "unit": "bytes"}）
 *  - 字符串（"24 bytes", "0x10"）
 * 无法转换时返回保留原值的 Quantity 与诊断，而不是返回空值/默认值。
 */
object QuantityParser {

    fun size(element: JsonObject?, key: String, position: String, recordId: String?): Pair<Quantity?, List<Diagnostic>> {
        val rawNode = element?.get(key) ?: return null to emptyList()
        val rawText = when (rawNode) {
            is JsonPrimitive -> rawNode.content
            else -> rawNode.toString()
        }
        val diagnostics = mutableListOf<Diagnostic>()
        var unit: String? = null
        var numeric: Double? = null
        var convertible = true
        var reason: String? = null

        val unitNode = element["unit"] as? JsonPrimitive
        if (unitNode != null) {
            unit = unitNode.content
            numeric = (rawNode as? JsonPrimitive)?.let { it.longOrNull?.toDouble() ?: it.doubleOrNull }
            if (numeric == null) {
                convertible = false
                reason = "size value is not numeric: '$rawText'"
            }
        } else {
            val parsed = parseTextWithUnit(rawText)
            numeric = parsed.first
            unit = parsed.second
            if (numeric == null) {
                convertible = false
                reason = "cannot parse size with unit: '$rawText'"
            }
        }

        val multiplier = unit?.let { SIZE_UNIT_TO_BYTES[it.trim().lowercase()] }
        var bytes: Long? = null
        if (convertible && numeric != null) {
            if (multiplier == null) {
                convertible = false
                reason = "unknown size unit '${unit ?: ""}' in '$rawText'"
            } else {
                val exact = numeric!! * multiplier
                if (exact < 0 || exact % 1.0 != 0.0) {
                    convertible = false
                    reason = "size does not map to an integral byte count: '$rawText'"
                } else {
                    bytes = exact.toLong()
                }
            }
        }

        if (!convertible) {
            diagnostics += Diagnostic(
                code = "QUANTITY_UNCONVERTIBLE",
                severity = Severity.ERROR,
                message = reason ?: "unconvertible size",
                position = "$position/$key",
                recordId = recordId,
            )
        }
        return Quantity(
            raw = rawText,
            unit = unit,
            normalizedBytes = bytes,
            convertible = convertible,
            conversionDiagnostic = reason,
        ) to diagnostics
    }

    /** 行号/偏移：line 类坐标不是字节，保留原值，标记不可字节转换。 */
    fun line(element: JsonObject?, key: String, position: String): Pair<Quantity?, List<Diagnostic>> {
        val rawNode = element?.get(key) ?: return null to emptyList()
        val rawText = if (rawNode is JsonPrimitive) rawNode.content else rawNode.toString()
        val long = (rawNode as? JsonPrimitive)?.longOrNull ?: rawText.trim().toLongOrNull()
        if (long == null) {
            return Quantity(rawText, "line", convertible = false,
                conversionDiagnostic = "line coordinate is not an integer") to
                listOf(Diagnostic("QUANTITY_UNCONVERTIBLE", Severity.ERROR,
                    "invalid line coordinate '$rawText'", "$position/$key"))
        }
        return Quantity(rawText, "line", normalizedOffset = long) to emptyList()
    }

    fun offset(element: JsonObject?, key: String, position: String): Pair<Quantity?, List<Diagnostic>> {
        val rawNode = element?.get(key) ?: return null to emptyList()
        val rawText = if (rawNode is JsonPrimitive) rawNode.content else rawNode.toString()
        val (num, unit) = parseTextWithUnit(rawText)
        val multiplier = unit?.let { SIZE_UNIT_TO_BYTES[it.lowercase()] }
        val diagnostics = mutableListOf<Diagnostic>()
        var value: Long? = null
        var convertible = true
        if (num == null) {
            convertible = false
            diagnostics += Diagnostic("QUANTITY_UNCONVERTIBLE", Severity.ERROR,
                "cannot parse offset '$rawText'", "$position/$key")
        } else if (multiplier == null) {
            convertible = false
            diagnostics += Diagnostic("QUANTITY_UNCONVERTIBLE", Severity.ERROR,
                "unknown offset unit in '$rawText'", "$position/$key")
        } else {
            value = (num * multiplier).toLong()
        }
        return Quantity(rawText, unit, normalizedOffset = value,
            convertible = convertible,
            conversionDiagnostic = diagnostics.firstOrNull()?.message) to diagnostics
    }

    private fun parseTextWithUnit(text: String): Pair<Double?, String?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null to null
        val match = Regex("^(0x[0-9a-fA-F]+|#[0-9a-fA-F]+|[0-9]*\\.?[0-9]+)\\s*([A-Za-z]+)?$").matchEntire(trimmed)
            ?: return null to null
        val numberPart = match.groupValues[1]
        val unitPart = match.groupValues[2].ifEmpty { "bytes" }
        val number: Double? = when {
            numberPart.startsWith("0x") -> numberPart.substring(2).toLongOrNull(16)?.toDouble()
            numberPart.startsWith("#") -> numberPart.substring(1).toLongOrNull(16)?.toDouble()
            else -> numberPart.toDoubleOrNull()
        }
        return number to unitPart
    }
}
