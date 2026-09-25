package heap

/** Edge kinds preserved verbatim from the snapshot material. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootKind { JNI_GLOBAL, THREAD, CLASS_LOADER, SYSTEM, STACK, OTHER }

/** Explicit policy deciding whether a weak edge keeps its target alive. */
enum class WeakPolicy { KEEP, DROP }

enum class Severity { ERROR, WARNING }

/** A positioned diagnostic. Never silently dropped; raw text is preserved. */
data class Diag(
    val line: Int,
    val col: Int,
    val code: String,
    val message: String,
    val raw: String,
    val severity: Severity = Severity.ERROR,
)

data class ClassMeta(
    val id: String,
    val name: String,
    val superName: String?,
    val raw: String,
    val line: Int,
)

data class AllocSite(
    val id: String,
    val classId: String?,
    val file: String?,
    val lineNo: Int?,
    val raw: String,
    val line: Int,
)

data class HeapObject(
    val id: Long,
    /** Address as written in the source. NOT a stable identity: duplicates are legal. */
    val addr: String,
    val classId: String?,
    /** Raw size token as imported (unit: bytes once normalized). */
    val sizeRaw: String,
    /** Normalized size in bytes; null when the raw token could not be converted. */
    val sizeBytes: Long?,
    val siteId: String?,
    val incomplete: Boolean,
    val raw: String,
    val line: Int,
)

data class HeapEdge(
    val from: Long,
    val to: Long,
    val kind: EdgeKind,
    val field: String?,
    val raw: String,
    val line: Int,
)

data class RootRef(
    val objectId: Long,
    val kind: RootKind,
    val raw: String,
    val line: Int,
)

/**
 * Immutable snapshot material. Everything the parser saw is preserved:
 * raw text, raw lines per entity, incomplete blocks, parser version.
 */
data class Snapshot(
    val id: String,
    val label: String,
    val parserVersion: String,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<RootRef>,
    val sites: List<AllocSite>,
    /** Malformed / unparseable source lines, preserved verbatim with diagnostics. */
    val incompleteBlocks: List<Diag>,
    val diagnostics: List<Diag>,
    val rawText: String,
) {
    val objectById: Map<Long, HeapObject> = objects.associateBy { it.id }
    val classById: Map<String, ClassMeta> = classes.associateBy { it.id }
    fun errors(): List<Diag> = diagnostics.filter { it.severity == Severity.ERROR }
}

/** Analysis parameters, versioned. weakPolicy is explicit, never implicit. */
data class AnalysisParams(
    val weakPolicy: WeakPolicy = WeakPolicy.KEEP,
    val budget: Int = 100_000,
    val maxRootPaths: Int = 32,
    val version: String = "rules/1.0",
)

/** Three distinct failure outcomes plus success — never collapsed into nulls/defaults. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class BudgetExhausted(val limit: Int, val needed: Int, val stage: String) : Outcome<Nothing>
    data class CorruptFormat(val diagnostics: List<Diag>) : Outcome<Nothing>
    data class Unsolvable(val reason: String, val context: String) : Outcome<Nothing>
}

fun <T> Outcome<T>.orNull(): T? = (this as? Outcome.Ok)?.value
