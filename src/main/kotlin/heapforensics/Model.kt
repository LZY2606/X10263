package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** 边类型：强引用 / 弱引用 / 内部引用（JVM 内部引用按强引用处理保活）。 */
@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class RootKind { THREAD, JNI_LOCAL, JNI_GLOBAL, CLASS_LOADER, SYSTEM_CLASS, OTHER }

@Serializable
data class ClassMeta(val id: String, val name: String, val superId: String? = null)

@Serializable
data class AllocSite(val id: String, val className: String, val method: String, val line: Int? = null)

@Serializable
data class HeapObject(
    val id: String,
    val address: String,            // 原始表示（如 "0x7f9c"），仅作展示，不作稳定身份
    val classId: String? = null,    // null => 不完整对象
    val sizeBytes: Long? = null,    // null => 不完整对象；单位：字节
    val allocSiteId: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class HeapEdge(val from: String, val to: String, val kind: EdgeKind, val name: String? = null)

@Serializable
data class HeapRoot(val id: String, val objectId: String, val kind: RootKind)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val classes: List<ClassMeta> = emptyList(),
    val allocSites: List<AllocSite> = emptyList(),
    val objects: List<HeapObject> = emptyList(),
    val edges: List<HeapEdge> = emptyList(),
    val roots: List<HeapRoot> = emptyList(),
    val incompleteBlocks: List<String> = emptyList(),
)

/** 带位置的诊断：path 为 JSON 路径，line/column 针对格式损坏。 */
@Serializable
data class Diagnostic(
    val path: String,
    val message: String,
    val severity: String = "error",   // error | warning
    val line: Int? = null,
    val column: Int? = null,
)

val snapshotJson = Json { prettyPrint = false; explicitNulls = false }

/** 规范化摘要：等价输入（数组顺序不同）得到相同 digest，保证稳定顺序。 */
fun canonicalDigest(s: HeapSnapshot): String {
    val c = s.copy(
        classes = s.classes.sortedBy { it.id },
        allocSites = s.allocSites.sortedBy { it.id },
        objects = s.objects.sortedBy { it.id },
        edges = s.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }, { it.name ?: "" })),
        roots = s.roots.sortedBy { it.id },
        incompleteBlocks = s.incompleteBlocks.sorted(),
    )
    val bytes = snapshotJson.encodeToString(HeapSnapshot.serializer(), c).toByteArray(Charsets.UTF_8)
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(bytes).joinToString("") { "%02x".format(it) }
}

/** 字节单位格式化：所有领域量携带单位。 */
fun formatBytes(v: Long): String = when {
    v < 1024 -> "$v B"
    v < 1024 * 1024 -> "%.1f KiB".format(v / 1024.0)
    else -> "%.1f MiB".format(v / (1024.0 * 1024.0))
}
