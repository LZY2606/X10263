package heaproom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ExportDoc(
    val format: String = "heaproom-export",
    val formatVersion: Int = 1,
    val exportedAt: String,
    val tables: Map<String, List<Map<String, String?>>>,
    val checksum: String
)

object ExportImport {
    val TABLE_ORDER = listOf(
        "snapshots", "classes", "objects", "edges", "roots", "incomplete_blocks",
        "diagnostics", "param_sets", "events", "computations", "decisions"
    )
    private val ORDER_BY = mapOf(
        "snapshots" to "snapshot_id",
        "classes" to "snapshot_id, ord",
        "objects" to "snapshot_id, ord",
        "edges" to "snapshot_id, ord",
        "roots" to "snapshot_id, ord",
        "incomplete_blocks" to "snapshot_id, ord",
        "diagnostics" to "id",
        "param_sets" to "event_seq",
        "events" to "seq",
        "computations" to "snapshot_id, compute_id",
        "decisions" to "event_seq"
    )

    fun export(storage: Storage): String {
        val tables = LinkedHashMap<String, List<Map<String, String?>>>()
        storage.tx { c ->
            for (t in TABLE_ORDER) {
                val rows = mutableListOf<Map<String, String?>>()
                c.createStatement().executeQuery("SELECT * FROM $t ORDER BY ${ORDER_BY[t]}").use { rs ->
                    val md = rs.metaData
                    while (rs.next()) {
                        val row = LinkedHashMap<String, String?>()
                        for (i in 1..md.columnCount) row[md.getColumnName(i)] = rs.getString(i)
                        rows += row
                    }
                }
                tables[t] = rows
            }
        }
        val partial = ExportDoc(
            exportedAt = java.time.Instant.now().toString(),
            tables = tables,
            checksum = ""
        )
        val checksum = Digests.sha256(
            JsonObject(mapOf(
                "format" to JsonPrimitive(partial.format),
                "formatVersion" to JsonPrimitive(partial.formatVersion),
                "exportedAt" to JsonPrimitive(partial.exportedAt),
                "tables" to CanonicalJson.encodeToString(kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.serializer<String>(),
                    kotlinx.serialization.builtins.ListSerializer(
                        kotlinx.serialization.builtins.MapSerializer(
                            kotlinx.serialization.serializer<String>(),
                            kotlinx.serialization.serializer<String?>()
                        )
                    )
                ), tables).let { JsonPrimitive(it) }
            )).toString()
        )
        return JsonFmt.encodeToString(ExportDoc.serializer(), partial.copy(checksum = checksum))
    }

    fun importText(storage: Storage, text: String): ImportOutcome {
        val doc: ExportDoc = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(ExportDoc.serializer(), text)
        } catch (t: Throwable) {
            return ImportOutcome.CorruptFormat(position = "document", message = "导出包解析失败: ${t.message}")
        }
        if (doc.format != "heaproom-export")
            return ImportOutcome.CorruptFormat("format", "不是 heaproom 导出包: ${doc.format}")
        val recomputed = Digests.sha256(
            JsonObject(mapOf(
                "format" to JsonPrimitive(doc.format),
                "formatVersion" to JsonPrimitive(doc.formatVersion),
                "exportedAt" to JsonPrimitive(doc.exportedAt),
                "tables" to JsonPrimitive(
                    CanonicalJson.encodeToString(
                        kotlinx.serialization.builtins.MapSerializer(
                            kotlinx.serialization.serializer<String>(),
                            kotlinx.serialization.builtins.ListSerializer(
                                kotlinx.serialization.builtins.MapSerializer(
                                    kotlinx.serialization.serializer<String>(),
                                    kotlinx.serialization.serializer<String?>()
                                )
                            )
                        ), doc.tables
                    )
                )
            )).toString()
        )
        if (recomputed != doc.checksum)
            return ImportOutcome.CorruptFormat("checksum", "校验和不匹配：导出包被截断、半写或篡改，拒绝导入")
        for (t in TABLE_ORDER) if (!doc.tables.containsKey(t))
            return ImportOutcome.CorruptFormat("tables.$t", "缺少表 $t")

        return try {
            storage.tx { c ->
                c.createStatement().use { s ->
                    for (t in TABLE_ORDER) s.executeUpdate("DELETE FROM $t")
                    s.executeUpdate("DELETE FROM sqlite_sequence")
                }
                for (t in TABLE_ORDER) {
                    val rows = doc.tables[t]!!
                    if (rows.isEmpty()) continue
                    val cols = rows.first().keys.toList()
                    val sql = "INSERT INTO $t(${cols.joinToString(",")}) VALUES(${cols.joinToString(",") { "?" }})"
                    c.prepareStatement(sql).use { p ->
                        for ((i, row) in rows.withIndex()) {
                            cols.forEachIndexed { ci, col ->
                                val v = row[col]
                                if (v == null) p.setNull(ci + 1, java.sql.Types.VARCHAR) else p.setString(ci + 1, v)
                            }
                            try {
                                p.executeUpdate()
                            } catch (t2: Throwable) {
                                throw IllegalStateException("表 $t 第 $i 行写入失败: ${t2.message}", t2)
                            }
                        }
                    }
                }
            }
            ImportOutcome.Ok("export@${doc.exportedAt}", emptyList())
        } catch (t: Throwable) {
            ImportOutcome.CorruptFormat("tables", "导入中途失败已整体回滚（半写恢复）: ${t.message}")
        }
    }
}
