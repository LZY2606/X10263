package heaplab

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

val heapJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    prettyPrint = true
}

/**
 * 快照导入校验：
 *  - ERROR（结构损坏/引用断裂/身份重复）-> 拒绝导入，返回带位置诊断；
 *  - WARNING（地址/大小不可转换、地址重复、不完整块等）-> 原样保留字段，规范化值缺失。
 * 绝不以空值或默认值伪装成功。
 */
object SnapshotIO {

    fun parse(text: String): Pair<Snapshot?, List<Diagnostic>> {
        val snapshot = try {
            heapJson.decodeFromString(Snapshot.serializer(), text)
        } catch (e: SerializationException) {
            return null to listOf(Diagnostic("$", "格式损坏，无法解析：${e.message}", Severity.ERROR))
        } catch (e: IllegalArgumentException) {
            return null to listOf(Diagnostic("$", "格式损坏，无法解析：${e.message}", Severity.ERROR))
        }
        return snapshot to validate(snapshot)
    }

    fun validate(s: Snapshot): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()
        fun err(path: String, msg: String) = diags.add(Diagnostic(path, msg, Severity.ERROR))
        fun warn(path: String, msg: String) = diags.add(Diagnostic(path, msg, Severity.WARNING))

        if (s.snapshotId.isBlank()) err("$.snapshotId", "快照 id 为空，无法建立稳定身份")
        if (s.parserVersion.isBlank()) err("$.parserVersion", "缺少解析器版本，材料不可追溯")

        val classIds = HashSet<String>()
        s.classes.forEachIndexed { i, c ->
            if (!classIds.add(c.classId)) err("$.classes[$i].classId", "类 id 重复：${c.classId}")
        }
        s.classes.forEachIndexed { i, c ->
            if (c.superClassId != null && c.superClassId !in classIds)
                warn("$.classes[$i].superClassId", "父类元数据缺失：${c.superClassId}")
        }

        val siteIds = s.allocationSites.mapTo(HashSet()) { it.siteId }
        val objectIds = HashSet<String>()
        val addressToIds = HashMap<String, MutableList<String>>()
        s.objects.forEachIndexed { i, o ->
            val p = "$.objects[$i]"
            if (!objectIds.add(o.objectId)) err("$p.objectId", "对象 id 重复：${o.objectId}")
            if (o.classId !in classIds) err("$p.classId", "引用了不存在的类元数据：${o.classId}")
            if (o.address == null) warn("$p.address", "地址无法转换，保留原始表示：\"${o.addressRaw}\"")
            else addressToIds.getOrPut(o.addressRaw.trim()) { mutableListOf() }.add(o.objectId)
            if (o.sizeBytes == null) warn("$p.size", "大小无法转换，保留原始表示：\"${o.sizeRaw}\"，保留大小计算将缺省此项")
            if (o.allocSiteId != null && o.allocSiteId !in siteIds)
                warn("$p.allocSiteId", "引用了不存在的 allocation site：${o.allocSiteId}")
            if (o.incomplete) warn(p, "对象标记为不完整（incomplete=true），其字段可能缺失")
        }
        addressToIds.filterValues { it.size > 1 }.forEach { (addr, ids) ->
            warn("$.objects", "地址 $addr 被多个对象复用：$ids；地址不是稳定身份，以 objectId 为准")
        }

        val edgeIds = HashSet<String>()
        s.edges.forEachIndexed { i, e ->
            val p = "$.edges[$i]"
            if (!edgeIds.add(e.edgeId)) err("$p.edgeId", "边 id 重复：${e.edgeId}")
            if (e.fromId !in objectIds) err("$p.fromId", "边的起点对象不存在：${e.fromId}")
            if (e.toId !in objectIds) err("$p.toId", "边的终点对象不存在：${e.toId}")
        }
        s.roots.forEachIndexed { i, r ->
            if (r.objectId !in objectIds) err("$.roots[$i].objectId", "root 指向不存在的对象：${r.objectId}")
        }
        s.incompleteBlocks.forEachIndexed { i, b ->
            warn("$.incompleteBlocks[$i]", "不完整块 ${b.blockId} 已保留：${b.reason}")
        }
        if (s.roots.isEmpty()) warn("$.roots", "快照没有 root，可达图将为空")
        return diags.sortedWith(compareBy({ it.severity.ordinal }, { it.path }))
    }

    private val SIZE_SUFFIX = mapOf(
        "b" to 1L, "byte" to 1L, "bytes" to 1L,
        "kb" to 1000L, "mb" to 1_000_000L, "gb" to 1_000_000_000L,
        "kib" to 1024L, "mib" to 1024L * 1024, "gib" to 1024L * 1024 * 1024,
    )

    fun parseAddress(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16) else t.toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parseSize(raw: String): Long? {
        val t = raw.trim().lowercase().replace(" ", "")
        if (t.isEmpty()) return null
        val suffix = SIZE_SUFFIX.keys.firstOrNull { t.endsWith(it) } ?: return t.toLongOrNull()
        val num = t.removeSuffix(suffix)
        val n = num.toLongOrNull() ?: return null
        val mul = SIZE_SUFFIX.getValue(suffix)
        return if (n > Long.MAX_VALUE / mul) null else n * mul
    }
}
