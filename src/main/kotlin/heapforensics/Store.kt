package heapforensics

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** Machine-readable event. Every accept / reject / parameter adjustment and
 *  every computation becomes one of these; restoring an old event restores
 *  the dependency versions and visible conclusions of that moment. */
@Serializable
data class Event(
    val id: Long,
    val type: String,
    val payload: JsonObject,
    val createdAt: String,
)

object EventType {
    const val IMPORT_SNAPSHOT = "IMPORT_SNAPSHOT"
    const val PARAM_REVISION = "PARAM_REVISION"
    const val SOURCE_PATCH = "SOURCE_PATCH"
    const val DECISION = "DECISION"
    const val COMPUTED = "COMPUTED"
    const val EXPORT = "EXPORT"
    const val IMPORT = "IMPORT"
}

class Store(private val dbPath: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
        createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE IF NOT EXISTS snapshots(
                    snapshot_id TEXT PRIMARY KEY,
                    json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS events(
                    id INTEGER PRIMARY KEY,
                    type TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS results(
                    snapshot_id TEXT NOT NULL,
                    param_version INTEGER NOT NULL,
                    json TEXT NOT NULL,
                    event_id INTEGER NOT NULL,
                    PRIMARY KEY(snapshot_id, param_version)
                );
                """.trimIndent()
            )
        }
        if (getMeta("schemaVersion") == null) setMeta("schemaVersion", "1")
    }

    fun close() = conn.close()

    fun getMeta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getString(1) else null
        }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    /** Append an event inside a transaction; a failure mid-write leaves no
     *  partial rows (half-write recovery at the storage layer). */
    fun appendEvent(type: String, payload: JsonObject, explicitId: Long? = null): Event {
        val createdAt = java.time.Instant.now().toString()
        conn.autoCommit = false
        try {
            val id: Long
            if (explicitId != null) {
                conn.prepareStatement("INSERT INTO events(id,type,payload,created_at) VALUES(?,?,?,?)").use {
                    it.setLong(1, explicitId); it.setString(2, type)
                    it.setString(3, payload.toString()); it.setString(4, createdAt); it.executeUpdate()
                }
                id = explicitId
            } else {
                conn.prepareStatement("INSERT INTO events(type,payload,created_at) VALUES(?,?,?)").use {
                    it.setString(1, type); it.setString(2, payload.toString()); it.setString(3, createdAt); it.executeUpdate()
                }
                conn.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT last_insert_rowid()"); rs.next(); id = rs.getLong(1)
                }
            }
            conn.commit()
            return Event(id, type, payload, createdAt)
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun events(): List<Event> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id,type,payload,created_at FROM events ORDER BY id")
            generateSequence { if (rs.next()) Event(rs.getLong(1), rs.getString(2), Json.parseToJsonElement(rs.getString(3)).jsonObject, rs.getString(4)) else null }.toList()
        }

    fun saveSnapshot(s: HeapSnapshot) {
        conn.prepareStatement("INSERT INTO snapshots(snapshot_id,json) VALUES(?,?) ON CONFLICT(snapshot_id) DO UPDATE SET json=excluded.json").use {
            it.setString(1, s.snapshotId)
            it.setString(2, Json.encodeToString(HeapSnapshot.serializer(), s))
            it.executeUpdate()
        }
    }

    fun loadSnapshot(id: String): HeapSnapshot? =
        conn.prepareStatement("SELECT json FROM snapshots WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) Json.decodeFromString(HeapSnapshot.serializer(), rs.getString(1)) else null
        }

    fun snapshotIds(): List<String> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT snapshot_id FROM snapshots ORDER BY snapshot_id")
            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }

    fun saveResult(r: AnalysisResult, eventId: Long) {
        conn.prepareStatement("INSERT INTO results(snapshot_id,param_version,json,event_id) VALUES(?,?,?,?) ON CONFLICT(snapshot_id,param_version) DO UPDATE SET json=excluded.json, event_id=excluded.event_id").use {
            it.setString(1, r.snapshotId); it.setInt(2, r.paramVersion)
            it.setString(3, Json.encodeToString(AnalysisResult.serializer(), r)); it.setLong(4, eventId)
            it.executeUpdate()
        }
    }

    fun loadResult(snapshotId: String, paramVersion: Int): AnalysisResult? =
        conn.prepareStatement("SELECT json FROM results WHERE snapshot_id=? AND param_version=?").use { ps ->
            ps.setString(1, snapshotId); ps.setInt(2, paramVersion)
            val rs = ps.executeQuery()
            if (rs.next()) Json.decodeFromString(AnalysisResult.serializer(), rs.getString(1)) else null
        }

    fun results(): List<AnalysisResult> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT json FROM results ORDER BY snapshot_id, param_version")
            generateSequence { if (rs.next()) Json.decodeFromString(AnalysisResult.serializer(), rs.getString(1)) else null }.toList()
        }

    // ---- Export / import roundtrip -------------------------------------
    // Identity, order and provenance are preserved: events keep their ids,
    // snapshots and results keep their keys. The payload carries a checksum;
    // a truncated or corrupted export is rejected as FORMAT_CORRUPT.

    fun exportJson(): String {
        val payload = buildJsonObject {
            put("format", "heap-forensics-export/1")
            put("meta", JsonObject(listOf("schemaVersion").associateWith { JsonPrimitive(getMeta(it) ?: "") }))
            putJsonArray("snapshots") { snapshotIds().forEach { id -> add(Json.parseToJsonElement(Json.encodeToString(HeapSnapshot.serializer(), loadSnapshot(id)!!))) } }
            putJsonArray("events") { events().forEach { e -> add(Json.encodeToJsonElement(Event.serializer(), e)) } }
            putJsonArray("results") { results().forEach { r -> add(Json.parseToJsonElement(Json.encodeToString(AnalysisResult.serializer(), r))) } }
        }
        val body = payload.toString()
        val checksum = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("checksum", checksum)
            put("payload", Json.parseToJsonElement(body))
        }.toString()
    }

    fun importJson(text: String): AnalysisOutcome<Int> {
        val envelope = try { Json.parseToJsonElement(text).jsonObject }
        catch (e: Exception) { return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$", "export is not valid JSON: ${e.message}"))) }
        val checksum = envelope["checksum"]?.jsonPrimitive?.contentOrNull
        val payload = envelope["payload"]?.jsonObject
        if (checksum == null || payload == null)
            return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$", "export missing checksum or payload")))
        val actual = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (actual != checksum)
            return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$.checksum", "checksum mismatch: export is truncated or corrupted")))
        if (payload["format"]?.jsonPrimitive?.contentOrNull != "heap-forensics-export/1")
            return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$.format", "unsupported export format")))

        var count = 0
        conn.autoCommit = false
        try {
            payload["snapshots"]!!.jsonArray.forEach {
                val s = Json.decodeFromJsonElement(HeapSnapshot.serializer(), it)
                saveSnapshot(s); count++
            }
            payload["events"]!!.jsonArray.forEach {
                val e = Json.decodeFromJsonElement(Event.serializer(), it)
                conn.prepareStatement("INSERT INTO events(id,type,payload,created_at) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING").use { ps ->
                    ps.setLong(1, e.id); ps.setString(2, e.type); ps.setString(3, e.payload.toString()); ps.setString(4, e.createdAt); ps.executeUpdate()
                }
                count++
            }
            payload["results"]!!.jsonArray.forEach {
                val r = Json.decodeFromJsonElement(AnalysisResult.serializer(), it)
                saveResult(r, 0); count++
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$", "import failed mid-write, rolled back: ${e.message}")))
        } finally {
            conn.autoCommit = true
        }
        return AnalysisOutcome.Ok(count)
    }
}
