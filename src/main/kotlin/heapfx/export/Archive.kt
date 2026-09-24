package heapfx.export

import heapfx.db.Database
import kotlinx.serialization.json.*

/**
 * Full-fidelity archive: every table is dumped row-by-row in storage order and
 * re-inserted verbatim on import, so identities, ordering and provenance survive
 * an export -> delete database -> import round trip.
 */
class Archive(val db: Database) {
    private val tables = listOf(
        "snapshots", "classes", "raw_objects", "raw_edges", "raw_roots", "incomplete_blocks",
        "policies", "computations", "derived_objects", "root_paths",
        "events", "decisions", "diagnostics", "counterexamples", "import_sessions",
    )

    fun export(): JsonObject {
        val root = mutableMapOf<String, JsonElement>()
        root["archiveFormat"] = JsonPrimitive("heapfx-archive/1")
        root["exportedAt"] = JsonPrimitive(java.time.Instant.now().toString())
        val tablesJson = mutableMapOf<String, JsonElement>()
        for (table in tables) {
            val rows = mutableListOf<JsonObject>()
            db.conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT * FROM $table")
                val md = rs.metaData
                while (rs.next()) {
                    val row = mutableMapOf<String, JsonElement>()
                    for (c in 1..md.columnCount) {
                        val name = md.getColumnName(c)
                        row[name] = when (val v = rs.getObject(c)) {
                            null -> JsonNull
                            is Number -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    }
                    rows.add(JsonObject(row))
                }
            }
            tablesJson[table] = JsonArray(rows)
        }
        root["tables"] = JsonObject(tablesJson)
        return JsonObject(root)
    }

    /** Imports an archive into the current (expected empty) database, preserving all ids. */
    fun import(archive: JsonObject) {
        val format = (archive["archiveFormat"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("archiveFormat missing: not a heapfx archive")
        if (format != "heapfx-archive/1") throw IllegalArgumentException("unsupported archive format '$format'")
        val tablesJson = archive["tables"]?.jsonObject ?: throw IllegalArgumentException("archive has no tables")
        db.tx {
            for (table in tables) {
                val rows = tablesJson[table]?.jsonArray ?: continue
                for (rowEl in rows) {
                    val row = rowEl.jsonObject
                    val cols = row.keys.toList()
                    val sql = "INSERT INTO $table(${cols.joinToString(",")}) VALUES(${cols.joinToString(",") { "?" }})"
                    db.conn.prepareStatement(sql).use { ps ->
                        cols.forEachIndexed { i, col ->
                            val v = row[col]!!
                            when {
                                v is JsonNull -> ps.setObject(i + 1, null)
                                v is JsonPrimitive && v.isString -> ps.setString(i + 1, v.content)
                                v is JsonPrimitive -> {
                                    val c = v.content
                                    val l = c.toLongOrNull()
                                    if (l != null) ps.setLong(i + 1, l) else ps.setString(i + 1, c)
                                }
                                else -> ps.setString(i + 1, v.toString())
                            }
                        }
                        ps.executeUpdate()
                    }
                }
            }
        }
    }
}
