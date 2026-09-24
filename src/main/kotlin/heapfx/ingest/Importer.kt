package heapfx.ingest

import heapfx.db.Database
import heapfx.model.*
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class ImportFailure(val status: ComputeStatus, message: String, val diagnostics: List<Diagnostic>) : Exception(message)

/**
 * Imports a snapshot payload into the immutable raw layer.
 * Raw ids, addresses, class metadata, edge kinds, root types, incomplete blocks and the
 * parser version are stored verbatim; normalization is deferred to the derived layer.
 * The whole import runs in one transaction tracked by import_sessions, so a crash
 * mid-import is rolled back and cleaned up by recoverIncompleteImports().
 */
class Importer(val db: Database) {
    private val json = Json { ignoreUnknownKeys = true }

    fun import(rawJson: String): Pair<String, List<Diagnostic>> {
        val payload: SnapshotPayload = try {
            json.decodeFromString(rawJson)
        } catch (e: Exception) {
            throw ImportFailure(
                ComputeStatus.FORMAT_CORRUPT, "snapshot payload is not valid: ${e.message}",
                listOf(Diagnostic("ERROR", "$", "unparseable snapshot document: ${e.message}")))
        }
        val diagnostics = mutableListOf<Diagnostic>()
        if (payload.snapshotId.isBlank())
            throw ImportFailure(ComputeStatus.FORMAT_CORRUPT, "missing snapshotId",
                listOf(Diagnostic("ERROR", "$.snapshotId", "snapshot id must not be blank")))
        if (payload.parserVersion.isBlank())
            diagnostics += Diagnostic("WARNING", "$.parserVersion", "parser version missing; recorded as empty")

        val sessionId = "imp-" + System.nanoTime()
        db.tx {
            db.conn.prepareStatement("INSERT INTO import_sessions(session_id,state,snapshot_id,started_ts) VALUES(?,?,?,?)").use { ps ->
                ps.setString(1, sessionId); ps.setString(2, "started")
                ps.setString(3, payload.snapshotId); ps.setString(4, java.time.Instant.now().toString()); ps.executeUpdate()
            }
            db.purgeSnapshot(payload.snapshotId) // re-import replaces raw material atomically
            val hash = MessageDigest.getInstance("SHA-256").digest(rawJson.toByteArray()).joinToString("") { "%02x".format(it) }
            db.conn.prepareStatement("INSERT INTO snapshots(snapshot_id,name,parser_version,captured_at,source_hash,imported_seq) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, payload.snapshotId); ps.setString(2, payload.name)
                ps.setString(3, payload.parserVersion); ps.setString(4, payload.capturedAt)
                ps.setString(5, hash); ps.setLong(6, db.nextSeq("snapshots", "imported_seq")); ps.executeUpdate()
            }
            payload.classes.forEachIndexed { i, c ->
                db.conn.prepareStatement("INSERT INTO classes(snapshot_id,class_id,name,super_name,class_loader,seq) VALUES(?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, payload.snapshotId); ps.setString(2, c.classId); ps.setString(3, c.name)
                    ps.setString(4, c.superName); ps.setString(5, c.classLoader); ps.setInt(6, i); ps.executeUpdate()
                }
            }
            val seenAddresses = mutableMapOf<String, String>()
            payload.objects.forEachIndexed { i, o ->
                val key = "${payload.snapshotId}/${o.id}"
                db.conn.prepareStatement("INSERT INTO raw_objects(snapshot_id,object_key,raw_id,address_raw,class_id,size_raw,alloc_site,incomplete,seq) VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, payload.snapshotId); ps.setString(2, key); ps.setString(3, o.id)
                    ps.setString(4, o.address); ps.setString(5, o.classId); ps.setString(6, o.size)
                    ps.setString(7, o.allocSite); ps.setInt(8, if (o.incomplete) 1 else 0); ps.setInt(9, i); ps.executeUpdate()
                }
                // Addresses are not identity: duplicates are flagged, both objects keep their raw ids.
                val prev = seenAddresses.putIfAbsent(o.address, o.id)
                if (prev != null) diagnostics += Diagnostic("WARNING", "$.objects[$i].address",
                    "duplicate address ${o.address}: previously used by object '$prev'; identity stays with raw id '${o.id}'")
                if (o.incomplete) diagnostics += Diagnostic("WARNING", "$.objects[$i]",
                    "object '${o.id}' flagged incomplete by parser; raw fields preserved")
            }
            payload.edges.forEachIndexed { i, e ->
                val kind = runCatching { EdgeKind.valueOf(e.kind) }.getOrNull()
                if (kind == null) diagnostics += Diagnostic("ERROR", "$.edges[$i].kind",
                    "unknown edge kind '${e.kind}'; edge stored but excluded from traversal")
                db.conn.prepareStatement("INSERT INTO raw_edges(snapshot_id,from_key,to_key,kind,name,seq) VALUES(?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, payload.snapshotId); ps.setString(2, "${payload.snapshotId}/${e.from}")
                    ps.setString(3, "${payload.snapshotId}/${e.to}"); ps.setString(4, e.kind)
                    ps.setString(5, e.name); ps.setInt(6, i); ps.executeUpdate()
                }
            }
            payload.roots.forEachIndexed { i, r ->
                db.conn.prepareStatement("INSERT INTO raw_roots(snapshot_id,object_key,root_type,seq) VALUES(?,?,?,?)").use { ps ->
                    ps.setString(1, payload.snapshotId); ps.setString(2, "${payload.snapshotId}/${r.`object`}")
                    ps.setString(3, r.type); ps.setInt(4, i); ps.executeUpdate()
                }
            }
            payload.incompleteBlocks.forEachIndexed { i, b ->
                db.conn.prepareStatement("INSERT INTO incomplete_blocks(snapshot_id,block_id,reason,affected_json,seq) VALUES(?,?,?,?,?)").use { ps ->
                    ps.setString(1, payload.snapshotId); ps.setString(2, b.blockId); ps.setString(3, b.reason)
                    ps.setString(4, db.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), b.affectedObjects))
                    ps.setInt(5, i); ps.executeUpdate()
                }
                diagnostics += Diagnostic("INFO", "$.incompleteBlocks[$i]",
                    "incomplete block '${b.blockId}' preserved: ${b.reason}")
            }
            diagnostics.forEachIndexed { i, d -> db.addDiagnostic("snapshot", payload.snapshotId, d, i) }
            db.conn.prepareStatement("UPDATE import_sessions SET state='committed' WHERE session_id=?").use { ps ->
                ps.setString(1, sessionId); ps.executeUpdate()
            }
        }
        return payload.snapshotId to diagnostics
    }
}
