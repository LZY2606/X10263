package heaproom

import java.security.MessageDigest

/**
 * 原始表示在导入层原样保留；只有派生层调用 normalizeSize 做规范化。
 * 返回 null 表示该单位无法转换为字节基准，调用方必须保留原值并挂诊断。
 */
object Units {
    private val toBytes: Map<String, Long> = mapOf(
        "B" to 1L,
        "KB" to 1_000L,
        "MB" to 1_000_000L,
        "GB" to 1_000_000_000L,
        "KiB" to 1_024L,
        "MiB" to 1_024L * 1_024L,
        "GiB" to 1_024L * 1_024L * 1_024L
    )

    fun normalizeSize(q: Quantity): Long? {
        val factor = toBytes[q.unit] ?: return null
        val v = q.raw.trim().toBigDecimalOrNull() ?: return null
        val scaled = v.multiply(factor.toBigDecimal())
        return if (scaled.stripTrailingZeros().scale() > 0) null else scaled.longValueExact()
    }
}

object Digests {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun sha256(canonicalJsonText: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonicalJsonText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
