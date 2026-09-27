package heapforensics.export

import heapforensics.domain.Diagnostic
import heapforensics.domain.Severity
import heapforensics.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

const val EXPORT_FORMAT = "heapsleuth-export"
const val EXPORT_FORMAT_VERSION = 1

sealed interface ImportOutcome {
    data class Ok(val inserted: Map<String, Int>, val skipped: Map<String, Int>) : ImportOutcome
    data class Corrupt(val diagnostics: List<Diagnostic>) : ImportOutcome
}

/** 导出/导入：身份（id）、顺序（seq）与来源关系（event_id/parents）全部保留。 */
object ExportImport {
    private val pretty = Json { prettyPrint = true }

    /** 数据部分（不含导出时间戳），用于往返一致性比较。 */
    fun exportData(store: Store): JsonObject = buildJsonObject {
        put("meta", buildJsonObject {
            store.allMeta().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        })
        put("snapshots", buildJsonArray {
            store.query("SELECT * FROM snapshots ORDER BY seq").forEach { rs ->
                add(rowObject(rs, listOf("snapshot_id", "input_digest", "source_name", "captured_at", "parser_version", "raw_json", "event_id", "batch_id", "seq")))
            }
        })
        put("events", buildJsonArray {
            store.query("SELECT * FROM events ORDER BY seq").forEach { rs ->
                add(rowObject(rs, listOf("event_id", "seq", "at", "type", "payload", "parents", "batch_id")))
            }
        })
        put("analyses", buildJsonArray {
            store.query("SELECT * FROM analyses ORDER BY seq").forEach { rs ->
                add(rowObject(rs, listOf("analysis_id", "snapshot_id", "input_digest", "param_version", "params_json", "outcome", "result_json", "outcome_detail", "event_id", "superseded_by", "invalidation_reason", "batch_id", "seq")))
            }
        })
        put("decisions", buildJsonArray {
            store.query("SELECT * FROM decisions ORDER BY seq").forEach { rs ->
                add(rowObject(rs, listOf("decision_id", "target", "verdict", "rationale", "author", "event_id", "batch_id", "seq")))
            }
        })
    }

    private fun rowObject(rs: java.sql.ResultSet, columns: List<String>): JsonObject = buildJsonObject {
        for (c in columns) {
            val v = rs.getString(c)
            if (v == null) put(c, JsonPrimitive(null as String?))
            else if (c == "seq") put(c, JsonPrimitive(rs.getLong(c)))
            else put(c, JsonPrimitive(v))
        }
    }

    fun export(store: Store): String = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", JsonPrimitive(EXPORT_FORMAT))
        put("formatVersion", JsonPrimitive(EXPORT_FORMAT_VERSION))
        put("exportedAt", JsonPrimitive(Instant.now().toString()))
        put("data", exportData(store))
    })

    fun import(store: Store, text: String): ImportOutcome {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ImportOutcome.Corrupt(listOf(Diagnostic(Severity.ERROR, "export.corrupt", "导出包 JSON 解析失败: ${e.message}", "\$")))
        }
        if (root["format"]?.jsonPrimitive?.content != EXPORT_FORMAT) {
            return ImportOutcome.Corrupt(listOf(Diagnostic(Severity.ERROR, "export.format", "不是有效的 $EXPORT_FORMAT 导出包", "format")))
        }
        val data = root["data"]?.jsonObject
            ?: return ImportOutcome.Corrupt(listOf(Diagnostic(Severity.ERROR, "export.data.missing", "导出包缺少 data 段", "data")))

        val inserted = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, Int>()

        fun insertRows(table: String, columns: List<String>, rows: JsonArray?) {
            var ins = 0
            var skip = 0
            rows?.forEach { el ->
                val obj = el.jsonObject
                val sql = "INSERT OR IGNORE INTO $table(${columns.joinToString(",")}) VALUES(${columns.joinToString(",") { "?" }})"
                val changed = store.conn.prepareStatement(sql).use { ps ->
                    columns.forEachIndexed { i, c ->
                        val v = obj[c]
                        if (v == null || v is JsonPrimitive && v.isString.not() && v.content == "null") ps.setObject(i + 1, null)
                        else if (v is JsonPrimitive && !v.isString && c == "seq") ps.setLong(i + 1, v.content.toLong())
                        else if (v is JsonPrimitive && v.isString) ps.setString(i + 1, v.content)
                        else ps.setString(i + 1, v.content)
                    }
                    ps.executeUpdate()
                }
                if (changed > 0) ins++ else skip++
            }
            inserted[table] = ins
            skipped[table] = skip
        }

        store.inBatch("export-import") { bid ->
            insertRows("snapshots", listOf("snapshot_id", "input_digest", "source_name", "captured_at", "parser_version", "raw_json", "event_id", "batch_id", "seq"), data["snapshots"]?.jsonArray)
            insertRows("events", listOf("event_id", "seq", "at", "type", "payload", "parents", "batch_id"), data["events"]?.jsonArray)
            insertRows("analyses", listOf("analysis_id", "snapshot_id", "input_digest", "param_version", "params_json", "outcome", "result_json", "outcome_detail", "event_id", "superseded_by", "invalidation_reason", "batch_id", "seq"), data["analyses"]?.jsonArray)
            insertRows("decisions", listOf("decision_id", "target", "verdict", "rationale", "author", "event_id", "batch_id", "seq"), data["decisions"]?.jsonArray)
            data["meta"]?.jsonObject?.forEach { (k, v) -> store.setMeta(k, v.jsonPrimitive.content) }
        }
        return ImportOutcome.Ok(inserted, skipped)
    }
}
