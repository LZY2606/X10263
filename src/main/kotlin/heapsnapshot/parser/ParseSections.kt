@file:JvmName("ParseSections")
package heapsnapshot.parser

import heapsnapshot.domain.*
import kotlinx.serialization.json.*

internal fun parseHeader(el: JsonObject, diags: DiagnosticCollector): SnapshotHeader {
    val loc = "$.header"
    val snapshotId = el["snapshotId"]?.rawStr() ?: run {
        diags.error("MISSING_SNAPSHOT_ID", "header.snapshotId 缺失", "$loc.snapshotId")
        "<unknown-snapshot>"
    }
    val parserVersion = el["parserVersion"]?.rawStr() ?: run {
        diags.error("MISSING_PARSER_VERSION", "解析器版本必须保留但缺失", "$loc.parserVersion")
        "<unknown-parser>"
    }
    val takenRaw = el["takenAt"]?.rawStr() ?: ""
    val takenEpoch = parseEpoch(takenRaw)
    if (takenRaw.isNotEmpty() && takenEpoch == null)
        diags.warn("UNCONVERTIBLE_TIME", "时间基准无法规范化，原始值保留: $takenRaw", "$loc.takenAt", takenRaw)
    return SnapshotHeader(
        snapshotId = snapshotId,
        parserVersion = parserVersion,
        formatVersion = el["formatVersion"]?.rawStr() ?: "unknown",
        takenAtRaw = takenRaw,
        takenAtEpochMs = takenEpoch,
        timeBasis = el["timeBasis"]?.rawStr() ?: "UTC",
        addressUnit = el["addressUnit"]?.rawStr() ?: "byte",
        sizeUnitRaw = el["sizeUnit"]?.rawStr() ?: "B",
        sizeUnitCanonical = canonicalUnit(el["sizeUnit"]?.rawStr() ?: "B"),
        raw = el
    )
}

private fun parseEpoch(raw: String): Long? {
    val n = raw.trim().toLongOrNull()
    if (n != null) return if (n < 10_000_000_000L) n * 1000 else n
    return try {
        java.time.Instant.parse(raw.trim()).toEpochMilli()
    } catch (_: Exception) { null }
}

internal fun canonicalUnit(raw: String): String = when (raw.trim().uppercase()) {
    "B", "BYTE", "BYTES" -> "B"
    "KB" -> "KB"
    "MB" -> "MB"
    "GB" -> "GB"
    else -> raw.uppercase()
}

internal fun parseClasses(root: JsonObject, diags: DiagnosticCollector): List<ClassMeta> {
    val arr = root["classes"]?.arr() ?: return emptyList()
    val seen = HashSet<String>()
    return arr.mapIndexedNotNull { i, ce ->
        val loc = "$.classes[$i]"
        val o = ce.obj() ?: run { diags.error("FORMAT_CORRUPT", "类条目不是对象", loc); return@mapIndexedNotNull null }
        val id = o["classId"]?.rawStr() ?: run { diags.error("MISSING_CLASS_ID", "类缺少 classId", loc); return@mapIndexedNotNull null }
        if (!seen.add(id)) diags.error("DUPLICATE_CLASS_ID", "重复 classId: $id", loc, id)
        ClassMeta(
            classId = id,
            name = o["name"]?.rawStr() ?: "<anonymous>",
            superClassId = o["superClassId"]?.rawStr(),
            renamedFromSnapshotId = o["renamedFromSnapshotId"]?.rawStr(),
            renamedFromClassId = o["renamedFromClassId"]?.rawStr(),
            raw = o
        )
    }.sortedBy { it.classId }
}

internal fun parseObjects(root: JsonObject, diags: DiagnosticCollector, sizeUnit: String): List<HeapObject> {
    val arr = root["objects"]?.arr() ?: return emptyList()
    val seen = HashSet<String>()
    val seenAddr = HashMap<String, String>()
    return arr.mapIndexedNotNull { i, oe ->
        val loc = "$.objects[$i]"
        val o = oe.obj() ?: run { diags.error("FORMAT_CORRUPT", "对象条目不是对象", loc); return@mapIndexedNotNull null }
        val id = o["objectId"]?.rawStr() ?: run { diags.error("MISSING_OBJECT_ID", "对象缺少 objectId", loc); return@mapIndexedNotNull null }
        if (!seen.add(id)) diags.error("DUPLICATE_OBJECT_ID", "重复 objectId: $id", loc, id)
        val addr = parseAddress(o["address"], "$loc.address", diags)
        if (addr.numeric != null) {
            val prior = seenAddr.put(addr.numeric.toString(), id)
            if (prior != null) diags.warn(
                "DUPLICATE_ADDRESS",
                "对象 $id 与 $prior 共享规范化地址 ${addr.numeric}; 地址不作为稳定身份，两者均保留",
                "$loc.address", addr.raw
            )
        }
        HeapObject(
            objectId = id,
            address = addr,
            classId = o["classId"]?.rawStr() ?: "<unknown-class>",
            shallowSize = parseSize(o["shallowSize"], loc, diags, sizeUnit),
            allocationSiteId = o["allocationSiteId"]?.rawStr(),
            incomplete = (o["incomplete"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            raw = o
        )
    }.sortedBy { it.objectId }
}

internal fun parseEdges(root: JsonObject, diags: DiagnosticCollector): List<Edge> {
    val arr = root["edges"]?.arr() ?: return emptyList()
    return arr.mapIndexedNotNull { i, ee ->
        val loc = "$.edges[$i]"
        val o = ee.obj() ?: run { diags.error("FORMAT_CORRUPT", "边条目不是对象", loc); return@mapIndexedNotNull null }
        val id = o["edgeId"]?.rawStr() ?: "edge@$i"
        val from = o["from"]?.rawStr() ?: o["fromObjectId"]?.rawStr()
        val to = o["to"]?.rawStr() ?: o["toObjectId"]?.rawStr()
        if (from == null || to == null) {
            diags.error("FORMAT_CORRUPT", "边缺少 from/to", loc); return@mapIndexedNotNull null
        }
        val kindRaw = o["kind"]?.rawStr() ?: "strong"
        val kind = parseEdgeKind(kindRaw)
        if (kind == EdgeKind.UNKNOWN)
            diags.warn("UNKNOWN_EDGE_KIND", "未知边类型 '$kindRaw'，按 UNKNOWN 保留且不参与可达性", "$loc.kind", kindRaw)
        Edge(id, from, to, kind, o["label"]?.rawStr(), o)
    }.sortedWith(compareBy({ it.fromObjectId }, { it.edgeId }))
}

internal fun parseEdgeKind(raw: String): EdgeKind = when (raw.trim().uppercase()) {
    "STRONG", "REF", "REFERENCE" -> EdgeKind.STRONG
    "WEAK", "WEAK_REF", "SOFT", "PHANTOM" -> EdgeKind.WEAK
    "INTERNAL", "INNER", "IMPLICIT" -> EdgeKind.INTERNAL
    else -> EdgeKind.UNKNOWN
}

internal fun parseRoots(root: JsonObject, diags: DiagnosticCollector): List<RootRecord> {
    val arr = root["roots"]?.arr() ?: return emptyList()
    return arr.mapIndexedNotNull { i, re ->
        val loc = "$.roots[$i]"
        val o = re.obj() ?: run { diags.error("FORMAT_CORRUPT", "root 条目不是对象", loc); return@mapIndexedNotNull null }
        val id = o["rootId"]?.rawStr() ?: "root@$i"
        val target = o["objectId"]?.rawStr()
        if (target == null) { diags.error("FORMAT_CORRUPT", "root 缺少 objectId", loc); return@mapIndexedNotNull null }
        val kindRaw = o["kind"]?.rawStr() ?: "UNKNOWN"
        RootRecord(id, target, parseRootKind(kindRaw), kindRaw, o["label"]?.rawStr(), o)
    }.sortedBy { it.rootId }
}

internal fun parseRootKind(raw: String): RootKind = when (raw.trim().uppercase().replace('-', '_').replace(' ', '_')) {
    "GC_ROOT" -> RootKind.GC_ROOT
    "JAVA_LOCAL", "LOCAL" -> RootKind.JAVA_LOCAL
    "JNI_GLOBAL" -> RootKind.JNI_GLOBAL
    "JNI_LOCAL" -> RootKind.JNI_LOCAL
    "STATIC_FIELD", "STATIC" -> RootKind.STATIC_FIELD
    "STICKY_CLASS" -> RootKind.STICKY_CLASS
    "MONITOR" -> RootKind.MONITOR
    "THREAD_OBJECT", "THREAD" -> RootKind.THREAD_OBJECT
    "INTERNED_STRING" -> RootKind.INTERNED_STRING
    "FINALIZING", "FINALIZABLE" -> RootKind.FINALIZING
    else -> RootKind.UNKNOWN
}

internal fun parseSites(root: JsonObject, diags: DiagnosticCollector): List<AllocationSite> {
    val arr = root["allocationSites"]?.arr() ?: return emptyList()
    return arr.mapIndexedNotNull { i, se ->
        val loc = "$.allocationSites[$i]"
        val o = se.obj() ?: run { diags.error("FORMAT_CORRUPT", "allocation site 条目不是对象", loc); return@mapIndexedNotNull null }
        val id = o["siteId"]?.rawStr() ?: return@mapIndexedNotNull null.also {
            diags.error("MISSING_SITE_ID", "allocation site 缺少 siteId", loc)
        }
        AllocationSite(
            siteId = id,
            locationRaw = o["location"]?.rawStr() ?: "",
            frameBasis = o["frameBasis"]?.rawStr(),
            line = o["line"]?.int(),
            column = o["column"]?.int(),
            raw = o
        )
    }.sortedBy { it.siteId }
}

internal fun parseIncompleteBlocks(root: JsonObject, diags: DiagnosticCollector, sizeUnit: String): List<IncompleteBlock> {
    val arr = root["incompleteBlocks"]?.arr() ?: return emptyList()
    return arr.mapIndexedNotNull { i, be ->
        val loc = "$.incompleteBlocks[$i]"
        val o = be.obj() ?: run { diags.error("FORMAT_CORRUPT", "不完整块条目不是对象", loc); return@mapIndexedNotNull null }
        val id = o["blockId"]?.rawStr() ?: "block@$i"
        IncompleteBlock(
            blockId = id,
            offsetStart = parseSize(o["offsetStart"], loc, diags, sizeUnit),
            offsetEnd = parseSize(o["offsetEnd"], loc, diags, sizeUnit),
            reason = o["reason"]?.rawStr() ?: "unspecified",
            raw = o
        )
    }.sortedBy { it.blockId }
}

internal fun crossValidate(
    objects: List<HeapObject>,
    classes: List<ClassMeta>,
    edges: List<Edge>,
    roots: List<RootRecord>,
    diags: DiagnosticCollector
) {
    val objIds = objects.mapTo(HashSet()) { it.objectId }
    val classIds = classes.mapTo(HashSet()) { it.classId }
    objects.forEach { o ->
        if (o.classId !in classIds)
            diags.warn("UNKNOWN_CLASS_REF", "对象 ${o.objectId} 引用未知类 ${o.classId}；元数据缺口保留", "$.objects(${o.objectId}).classId", o.classId)
        if (o.incomplete)
            diags.info("INCOMPLETE_OBJECT", "对象 ${o.objectId} 标记为不完整，派生结果将标注不确定性", "$.objects(${o.objectId})", o.objectId)
    }
    edges.forEach { e ->
        if (e.fromObjectId !in objIds) diags.error("EDGE_DANGLING", "边 ${e.edgeId} 的起点 ${e.fromObjectId} 不存在", "$.edges(${e.edgeId}).from", e.fromObjectId)
        if (e.toObjectId !in objIds) diags.error("EDGE_DANGLING", "边 ${e.edgeId} 的终点 ${e.toObjectId} 不存在", "$.edges(${e.edgeId}).to", e.toObjectId)
    }
    roots.forEach { r ->
        if (r.objectId !in objIds) diags.error("ROOT_DANGLING", "root ${r.rootId} 指向不存在对象 ${r.objectId}", "$.roots(${r.rootId})", r.objectId)
    }
}
