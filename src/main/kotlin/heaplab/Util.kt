package heaplab

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 统一时间基准：UTC，ISO-8601。 */
fun nowIso(): String =
    OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** 快照内容的规范化表示：排序后拼接，等价输入得到等价摘要与稳定顺序。 */
fun canonicalSnapshot(s: SnapshotData): String = buildString {
    append("parser=").append(s.parserVersion).append('\n')
    s.classes.sortedBy { it.id }.forEach {
        append("class|${it.id}|${it.name}|${it.superName ?: ""}|${it.loader ?: ""}\n")
    }
    s.objects.sortedBy { it.id }.forEach {
        append("object|${it.id}|${it.classId}|${it.addressRaw}|${it.sizeBytes?.toString() ?: "?"}|${it.allocSite ?: ""}\n")
    }
    s.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }, { it.field ?: "" })).forEach {
        append("edge|${it.from}|${it.to}|${it.kind}|${it.field ?: ""}\n")
    }
    s.roots.sortedBy { it.id }.forEach { append("root|${it.id}|${it.kind}|${it.target}\n") }
    s.allocSites.sortedBy { it.id }.forEach {
        append("alloc|${it.id}|${it.thread ?: ""}|${it.frames.joinToString(";")}\n")
    }
    s.incompletes.sortedBy { it.offset }.forEach {
        append("incomplete|${it.offset}|${it.length}|${it.reason}\n")
    }
}

fun snapshotDigest(s: SnapshotData): String = sha256Hex(canonicalSnapshot(s))

fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.2f MiB".format(bytes / (1024.0 * 1024.0))
}

fun escapeHtml(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")
