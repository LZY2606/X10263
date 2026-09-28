package heaplab.parse

import heaplab.domain.*
import kotlinx.serialization.json.Json

/**
 * 导入器。原则：
 *  - 原始表示全部保留（地址字符串、带单位大小、unknown 字段）。
 *  - 无法转换的值留在原位并附诊断，绝不用空值/默认值伪装成功。
 *  - 格式损坏、资源预算耗尽、语义无解是三种不同结果。
 */
object SnapshotLoader {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        encodeDefaults = true
    }

    data class Budgets(val maxNodes: Int, val maxEdges: Int)

    fun load(rawText: String, budgets: Budgets = Budgets(200_000, 1_000_000)): LoadResult {
        val element = try {
            json.parseToJsonElement(rawText)
        } catch (t: Throwable) {
            return LoadResult.Corrupt(
                listOf(
                    Diagnostic(
                        "json-malformed", Severity.ERROR,
                        "快照不是合法 JSON：${t.message ?: "无法解析"}",
                        location = "<root>",
                    )
                )
            )
        }
        val bundle = try {
            json.decodeFromJsonElement(RawBundle.serializer(), element)
        } catch (t: Throwable) {
            return LoadResult.Corrupt(
                listOf(
                    Diagnostic(
                        "schema-mismatch", Severity.ERROR,
                        "快照结构与 HSF schema 不符：${t.message ?: "结构错误"}",
                        location = "<root>",
                    )
                )
            )
        }
        return validate(bundle, budgets)
    }

    fun validate(bundle: RawBundle, budgets: Budgets): LoadResult {
        val diags = mutableListOf<Diagnostic>()
        val semanticCandidates = mutableListOf<String>()
        var nodes = 0L
        var edges = 0L

        if (bundle.isEmptyBundle()) {
            diags += Diagnostic("bundle-empty", Severity.ERROR, "bundle 不包含任何快照", "snapshots")
            return LoadResult.Corrupt(diags)
        }

        bundle.snapshots.forEachIndexed { si, snap ->
            val sp = "snapshots[$si]"
            if (snap.parserVersion.isBlank())
                diags += Diagnostic("parser-version-blank", Severity.ERROR, "解析器版本缺失", "$sp.parserVersion")
            if (snap.takenAt.isBlank())
                diags += Diagnostic("time-basis-missing", Severity.WARNING, "时间基准缺失", "$sp.takenAt")

            nodes += snap.objects.size
            edges += snap.edges.size

            val classIds = HashMap<String, Int>()
            snap.classes.forEachIndexed { ci, c ->
                val loc = "$sp.classes[$ci]"
                if (c.classId.isBlank()) diags += Diagnostic("class-id-blank", Severity.ERROR, "classId 为空", "$loc.classId")
                val prev = classIds.put(c.classId, ci)
                if (prev != null) diags += Diagnostic("duplicate-class-id", Severity.ERROR,
                    "classId=${c.classId} 重复（$loc 与 classes[$prev]）", loc)
            }

            val objectIds = HashMap<String, Int>()
            val addressToObjects = HashMap<String, MutableList<Int>>()
            snap.objects.forEachIndexed { oi, o ->
                val loc = "$sp.objects[$oi]"
                if (o.objectId.isBlank()) diags += Diagnostic("object-id-blank", Severity.ERROR, "objectId 为空", "$loc.objectId")
                val prev = objectIds.put(o.objectId, oi)
                if (prev != null)
                    diags += Diagnostic("duplicate-object-id", Severity.ERROR,
                        "objectId=${o.objectId} 重复，不能作为稳定身份", "$loc.objectId")
                // 地址不应当作稳定身份：记录重复地址为诊断，不做去重。
                addressToObjects.getOrPut(o.address) { mutableListOf() } += oi
                if (o.classId !in classIds)
                    diags += Diagnostic("dangling-class", Severity.ERROR,
                        "对象引用了不存在的 classId=${o.classId}", "$loc.classId")
                SizeCodec.inspect(o.shallowSize, o.sizeUnit, "$loc.shallowSize", diags)
                if (o.incomplete)
                    diags += Diagnostic("incomplete-object", Severity.WARNING,
                        "对象不完整：${o.incompleteReason ?: "未说明原因"}（保留原样，保留大小标记为区间）", loc)
            }
            addressToObjects.forEach { (addr, indexes) ->
                if (indexes.size > 1)
                    diags += Diagnostic("duplicate-address", Severity.WARNING,
                        "地址 $addr 被 ${indexes.size} 个对象使用；地址不能作为稳定身份",
                        "$sp.objects (address=$addr)")
            }

            snap.edges.forEachIndexed { ei, e ->
                val loc = "$sp.edges[$ei]"
                if (e.from !in objectIds)
                    diags += Diagnostic("dangling-edge-from", Severity.ERROR, "边起点 ${e.from} 不存在", "$loc.from")
                if (e.to !in objectIds)
                    diags += Diagnostic("dangling-edge-to", Severity.ERROR, "边终点 ${e.to} 不存在", "$loc.to")
                if (e.kind == EdgeKind.WEAK && e.weakReferenceName == null)
                    diags += Diagnostic("weak-unnamed", Severity.INFO, "弱引用未给出 reference 名称", loc)
            }
            snap.roots.forEachIndexed { ri, r ->
                val loc = "$sp.roots[$ri]"
                if (r.objectId !in objectIds)
                    diags += Diagnostic("dangling-root", Severity.ERROR, "root 指向不存在对象 ${r.objectId}", "$loc.objectId")
            }
            snap.incompleteChunks.forEachIndexed { ci, ch ->
                diags += Diagnostic("incomplete-chunk", Severity.WARNING,
                    "不完整块保留：${ch.reason}", "$sp.incompleteChunks[$ci]")
            }
        }

        // 跨快照身份对应：同一 stableId 指向冲突 -> 语义无解，保留全部合法候选。
        bundle.snapshots.forEach { snap ->
            val byStable = HashMap<String, MutableList<String>>()
            snap.objects.forEach { o ->
                if (o.stableId != null) byStable.getOrPut(o.stableId) { mutableListOf() } += o.objectId
            }
            bundle.identity.stableIdBySnapshotObject[snap.snapshotId]?.forEach { (objId, sid) ->
                if (objId !in snap.objects.associateBy { it.objectId })
                    diags += Diagnostic("identity-unknown-object", Severity.WARNING,
                        "身份对应引用了快照内不存在的对象 $objId",
                        "identity.stableIdBySnapshotObject.${snap.snapshotId}.$objId")
                byStable.getOrPut(sid) { mutableListOf() } += objId
            }
            byStable.forEach { (sid, members) ->
                val distinct = members.distinct()
                if (distinct.size > 1) {
                    semanticCandidates += "stableId=$sid 候选对象: ${distinct.joinToString()}"
                    diags += Diagnostic("ambiguous-identity", Severity.ERROR,
                        "stableId=$sid 在同一快照映射到多个对象，无法唯一决定（保留全部合法候选）",
                        "identity.stableIdBySnapshotObject.${snap.snapshotId}")
                }
            }
        }

        if (nodes > budgets.maxNodes || edges > budgets.maxEdges)
            return LoadResult.BudgetExhausted(diags, nodes + edges, (budgets.maxNodes + budgets.maxEdges).toLong())
        if (diags.any { it.severity == Severity.ERROR })
            return if (semanticCandidates.isNotEmpty())
                LoadResult.SemanticUnresolved(diags, semanticCandidates.distinct())
            else LoadResult.Corrupt(diags)
        return LoadResult.Ok(bundle, diags)
    }

    private fun RawBundle.isEmptyBundle(): Boolean = snapshots.isEmpty()
}

/** 领域量携带单位/坐标基准；派生层才规范化，无法转换留在原位并附诊断。 */
object SizeCodec {
    private val unitScale = mapOf("B" to 1L, "KB" to 1024L, "MB" to 1024L * 1024, "GB" to 1024L * 1024 * 1024)

    fun inspect(element: kotlinx.serialization.json.JsonElement, unit: String, location: String, diags: MutableList<Diagnostic>) {
        when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (unit !in unitScale)
                    diags += Diagnostic("unknown-size-unit", Severity.WARNING, "未知大小单位 '$unit'，保留原始值", location, element)
                if (element.content.toLongOrNull() == null && !(element.isString && Regex("""\d+(\.\d+)?""").containsMatchIn(element.content)))
                    diags += Diagnostic("unconvertible-size", Severity.WARNING,
                        "shallowSize '${element.content}' 无法转换为字节，保留在原位不参与规范化", location, element)
            }
            else -> diags += Diagnostic("unconvertible-size", Severity.WARNING,
                "shallowSize 不是数值/字符串标量，保留在原位", location, element)
        }
    }

    /** 派生层规范化：返回规范化字节，或 null 表示无法转换（由调用方保留原值）。 */
    fun toBytesOrNull(element: kotlinx.serialization.json.JsonElement, unit: String): Long? {
        val num = when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.content.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        val scale = unitScale[unit] ?: return null
        return (num * scale).toLong()
    }
}
