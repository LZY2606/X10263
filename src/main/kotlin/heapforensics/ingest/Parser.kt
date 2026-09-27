package heapforensics.ingest

import heapforensics.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SNAPSHOT_FORMAT = "heapsleuth-snapshot"
const val SNAPSHOT_FORMAT_VERSION = 1

/** 解析结果：成功（可带诊断）或格式损坏。语义无解在分析层判定。 */
sealed interface ParseOutcome {
    data class Ok(val snapshot: RawSnapshot, val diagnostics: List<Diagnostic>) : ParseOutcome
    data class Corrupt(val diagnostics: List<Diagnostic>) : ParseOutcome
}

@Serializable
private data class ClassDto(val id: String? = null, val name: String? = null, val superId: String? = null, val loader: String? = null)

@Serializable
private data class AllocDto(val file: String? = null, val line: Int? = null, val function: String? = null)

@Serializable
private data class ObjectDto(val id: String? = null, val address: String? = null, val classId: String? = null, val size: String? = null, val allocSite: AllocDto? = null)

@Serializable
private data class EdgeDto(val from: String? = null, val to: String? = null, val kind: String? = null, val name: String? = null)

@Serializable
private data class RootDto(val objectId: String? = null, val kind: String? = null)

@Serializable
private data class BlockDto(val offset: String? = null, val length: String? = null, val reason: String? = null)

@Serializable
private data class SnapshotDto(
    val format: String? = null,
    val formatVersion: Int? = null,
    val parserVersion: String? = null,
    val snapshotId: String? = null,
    val capturedAt: String? = null,
    val classes: List<ClassDto>? = null,
    val objects: List<ObjectDto>? = null,
    val edges: List<EdgeDto>? = null,
    val roots: List<RootDto>? = null,
    val incompleteBlocks: List<BlockDto>? = null
)

object SnapshotParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, sourceName: String): ParseOutcome {
        val dto: SnapshotDto = try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            return ParseOutcome.Corrupt(
                listOf(Diagnostic(Severity.ERROR, "format.corrupt", "JSON 解析失败: ${e.message}", "\$"))
            )
        }
        val errors = mutableListOf<Diagnostic>()
        fun err(code: String, msg: String, pos: String) {
            errors += Diagnostic(Severity.ERROR, code, msg, pos)
        }
        if (dto.format == null) err("format.missing", "缺少 format 字段", "format")
        else if (dto.format != SNAPSHOT_FORMAT) err("format.unsupported", "不支持的格式 '${dto.format}'，期望 '$SNAPSHOT_FORMAT'", "format")
        if (dto.formatVersion == null) err("format.version.missing", "缺少 formatVersion", "formatVersion")
        else if (dto.formatVersion > SNAPSHOT_FORMAT_VERSION)
            err("format.version.unsupported", "formatVersion ${dto.formatVersion} 高于支持的 $SNAPSHOT_FORMAT_VERSION", "formatVersion")
        if (dto.snapshotId.isNullOrBlank()) err("snapshot.id.missing", "缺少 snapshotId", "snapshotId")
        if (dto.capturedAt.isNullOrBlank()) err("snapshot.capturedAt.missing", "缺少 capturedAt（时间基准 ISO-8601）", "capturedAt")
        if (dto.classes == null) err("section.missing", "缺少 classes 数组", "classes")
        if (dto.objects == null) err("section.missing", "缺少 objects 数组", "objects")
        if (dto.edges == null) err("section.missing", "缺少 edges 数组", "edges")
        if (dto.roots == null) err("section.missing", "缺少 roots 数组", "roots")
        if (errors.isNotEmpty()) return ParseOutcome.Corrupt(errors)

        val diagnostics = mutableListOf<Diagnostic>()
        val classes = dto.classes!!.mapIndexed { i, c ->
            if (c.id == null || c.name == null) {
                errors += Diagnostic(Severity.ERROR, "class.field.missing", "类记录缺少 id/name", "classes[$i]")
                null
            } else RawClass(c.id, c.name, c.superId, c.loader)
        }
        val objects = dto.objects!!.mapIndexed { i, o ->
            if (o.id == null || o.address == null || o.classId == null || o.size == null) {
                errors += Diagnostic(Severity.ERROR, "object.field.missing", "对象记录缺少 id/address/classId/size", "objects[$i]")
                null
            } else RawObject(
                o.id, o.address, o.classId, o.size,
                o.allocSite?.let { AllocSite(it.file, it.line, it.function) }
            )
        }
        val edges = dto.edges!!.mapIndexed { i, e ->
            if (e.from == null || e.to == null || e.kind == null) {
                errors += Diagnostic(Severity.ERROR, "edge.field.missing", "边记录缺少 from/to/kind", "edges[$i]")
                null
            } else when (val kind = parseEnum<EdgeKind>(e.kind)) {
                null -> {
                    errors += Diagnostic(Severity.ERROR, "edge.kind.unknown", "未知边类型 '${e.kind}'（期望 strong/weak/internal）", "edges[$i].kind")
                    null
                }
                else -> RawEdge(e.from, e.to, kind, e.name)
            }
        }
        val roots = dto.roots!!.mapIndexed { i, r ->
            if (r.objectId == null || r.kind == null) {
                errors += Diagnostic(Severity.ERROR, "root.field.missing", "root 记录缺少 objectId/kind", "roots[$i]")
                null
            } else when (val kind = parseEnum<RootKind>(r.kind)) {
                null -> {
                    errors += Diagnostic(Severity.ERROR, "root.kind.unknown", "未知 root 类型 '${r.kind}'", "roots[$i].kind")
                    null
                }
                else -> RawRoot(r.objectId, kind)
            }
        }
        val blocks = (dto.incompleteBlocks ?: emptyList()).mapIndexed { i, b ->
            if (b.offset == null || b.length == null || b.reason == null) {
                errors += Diagnostic(Severity.ERROR, "block.field.missing", "不完整块缺少 offset/length/reason", "incompleteBlocks[$i]")
                null
            } else IncompleteBlock(b.offset, b.length, b.reason)
        }
        if (errors.isNotEmpty()) return ParseOutcome.Corrupt(errors)

        val parserVersion = dto.parserVersion ?: CURRENT_PARSER_VERSION
        if (dto.parserVersion == null) diagnostics += Diagnostic(
            Severity.INFO, "parser.version.assumed", "快照未声明解析器版本，记录当前解析器版本 $CURRENT_PARSER_VERSION", "parserVersion"
        )
        val snapshot = RawSnapshot(
            snapshotId = dto.snapshotId!!,
            capturedAt = dto.capturedAt!!,
            parserVersion = parserVersion,
            sourceName = sourceName,
            classes = classes.filterNotNull(),
            objects = objects.filterNotNull(),
            edges = edges.filterNotNull(),
            roots = roots.filterNotNull(),
            incompleteBlocks = blocks.filterNotNull()
        )
        return ParseOutcome.Ok(snapshot, diagnostics)
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw.replace('-', '_'), ignoreCase = true) }
}
