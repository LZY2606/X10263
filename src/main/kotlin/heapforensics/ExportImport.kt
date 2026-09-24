package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class ExportEnvelope(
    val formatVersion: Int,
    val snapshots: List<String>,          // raw snapshot JSON, original representation preserved
    val events: List<DomainEvent>,        // seq included: order and identity survive the roundtrip
    val runs: List<AnalysisRun>,
    val checksum: String                  // sha256 over the payload; detects truncation / half writes
)

sealed interface ImportOutcome {
    data class Ok(val snapshots: Int, val events: Int, val runs: Int) : ImportOutcome
    data class FormatCorrupt(val detail: String) : ImportOutcome
    data class Conflict(val detail: String) : ImportOutcome
}

object ExportImport {
    const val FORMAT_VERSION = 1
    private val json = Json { prettyPrint = true }

    fun exportAll(store: Store): String {
        val snapshots = store.listSnapshots().map { store.getSnapshotRaw(it.snapshotId)!! }
        val events = store.allEvents()
        val runs = store.runs()
        val payload = snapshots.joinToString("") + events.joinToString("") { it.eventId + it.seq } +
            runs.joinToString("") { it.runId }
        val env = ExportEnvelope(FORMAT_VERSION, snapshots, events, runs, Analysis.sha(payload))
        return json.encodeToString(env)
    }

    /** Atomic file export: write to temp, then move. A crash leaves either the old or the new file. */
    fun exportToFile(store: Store, target: Path) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(tmp, exportAll(store))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun importAll(store: Store, text: String): ImportOutcome {
        val env: ExportEnvelope = try {
            json.decodeFromString<ExportEnvelope>(text)
        } catch (e: Exception) {
            return ImportOutcome.FormatCorrupt("envelope unparseable: ${e.message}")
        }
        if (env.formatVersion != FORMAT_VERSION) {
            return ImportOutcome.FormatCorrupt("unsupported formatVersion ${env.formatVersion}")
        }
        val payload = env.snapshots.joinToString("") + env.events.joinToString("") { it.eventId + it.seq } +
            env.runs.joinToString("") { it.runId }
        if (Analysis.sha(payload) != env.checksum) {
            return ImportOutcome.FormatCorrupt("checksum mismatch: archive truncated or tampered")
        }
        // Identity conflict check: same id but different content is rejected.
        for (raw in env.snapshots) {
            val snap = try { Json.decodeFromString(HeapSnapshot.serializer(), raw) }
                catch (e: Exception) { return ImportOutcome.FormatCorrupt("snapshot unparseable: ${e.message}") }
            val existing = store.getSnapshotRaw(snap.snapshotId)
            if (existing != null && existing != raw) {
                return ImportOutcome.Conflict("snapshot ${snap.snapshotId} exists with different content")
            }
        }
        val existingEvents = store.allEvents().associateBy { it.eventId }
        for (e in env.events) {
            val ex = existingEvents[e.eventId]
            if (ex != null && (ex.summary != e.summary || ex.seq != e.seq)) {
                return ImportOutcome.Conflict("event ${e.eventId} exists with different content")
            }
        }
        // Apply: snapshots first, then events preserving original seq, then runs.
        for (raw in env.snapshots) {
            val snap = Json.decodeFromString(HeapSnapshot.serializer(), raw)
            store.saveSnapshot(snap, raw)
        }
        val known = store.allEvents().map { it.eventId }.toSet()
        val fresh = env.events.filter { it.eventId !in known }
        store.conn.autoCommit = false
        try {
            store.conn.prepareStatement(
                "INSERT INTO events(seq,event_id,type,snapshot_id,params_version,summary,affected,attributes,created_at,checksum) VALUES(?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (e in fresh.sortedBy { it.seq }) {
                    ps.setLong(1, e.seq); ps.setString(2, e.eventId); ps.setString(3, e.type)
                    ps.setString(4, e.snapshotId)
                    if (e.paramsVersion != null) ps.setInt(5, e.paramsVersion) else ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setString(6, e.summary)
                    ps.setString(7, Json.encodeToString(e.affected))
                    ps.setString(8, Json.encodeToString(e.attributes))
                    ps.setString(9, e.createdAt)
                    ps.setString(10, Analysis.sha(listOf(e.eventId, e.type, e.snapshotId, e.paramsVersion,
                        e.summary, e.affected.joinToString(","), e.attributes.toSortedMap().toString(), e.createdAt).joinToString("|")))
                    ps.executeUpdate()
                }
            }
            store.conn.commit()
        } catch (t: Throwable) {
            store.conn.rollback()
            return ImportOutcome.FormatCorrupt("event insert failed, rolled back: ${t.message}")
        } finally {
            store.conn.autoCommit = true
        }
        for (r in env.runs) store.saveRun(r)
        return ImportOutcome.Ok(env.snapshots.size, fresh.size, env.runs.size)
    }
}
