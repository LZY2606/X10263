package heaplab.model

import kotlinx.serialization.Serializable

/** Edge kinds in the heap graph. Weak edges only keep objects alive under an explicit policy. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootType { THREAD, JNI_GLOBAL, JNI_LOCAL, SYSTEM_CLASS, MONITOR, NATIVE_STACK, OTHER }

/** Explicit policy deciding whether weak references keep objects alive. */
enum class WeakPolicy { WEAK_NOT_RETAINED, WEAK_RETAINED }

enum class Severity { ERROR, WARNING }

@Serializable
data class Diagnostic(
    val position: String,      // e.g. "objects[3].size" — where in the input the problem is
    val code: String,          // machine-readable code, e.g. SIZE_UNPARSEABLE
    val message: String,
    val severity: Severity = Severity.ERROR,
)

/** Raw size as imported: original representation is preserved; normalization happens in the derived layer. */
@Serializable
data class Quantity(val raw: String, val unit: String) {
    /** Normalized value in bytes, or null when unconvertible (value stays in place + diagnostic). */
    fun toBytes(): Long? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        return when (unit.lowercase()) {
            "b", "byte", "bytes" -> v.toLong()
            "kb", "kib" -> (v * 1024).toLong()
            "mb", "mib" -> (v * 1024 * 1024).toLong()
            else -> null
        }
    }
}

@Serializable
data class ClassInfo(val classId: String, val name: String, val loader: String = "app")

@Serializable
data class HeapObject(
    val objectId: String,          // stable identity — address is NEVER used as identity
    val address: String,           // raw address, may duplicate across objects
    val classId: String,
    val size: Quantity,
    val allocationSite: String? = null, // e.g. "com.acme.Cache.put(Cache.java:42)"
    val incomplete: Boolean = false,    // object came from an incomplete chunk
)

@Serializable
data class Edge(val fromId: String, val toId: String, val kind: EdgeKind, val field: String = "")

@Serializable
data class Root(val objectId: String, val rootType: RootType, val description: String = "")

@Serializable
data class IncompleteChunk(val chunkId: String, val offset: Long, val length: Long, val reason: String, val rawHex: String = "")

@Serializable
data class HeapSnapshot(
    val snapshotId: String,        // stable snapshot identity
    val parserVersion: String,     // parser version is preserved
    val capturedAt: String,        // time base, ISO-8601
    val classes: List<ClassInfo>,
    val objects: List<HeapObject>,
    val edges: List<Edge>,
    val roots: List<Root>,
    val incompleteChunks: List<IncompleteChunk> = emptyList(),
)

/** Analysis parameters — versioned so every result records which rule version produced it. */
@Serializable
data class AnalysisParams(
    val version: String = "params/v1",
    val weakPolicy: WeakPolicy = WeakPolicy.WEAK_NOT_RETAINED,
    val maxSteps: Long = 1_000_000,   // resource budget
    val maxRootPathsPerObject: Int = 64,
)
