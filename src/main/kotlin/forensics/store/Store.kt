package forensics.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import forensics.ledger.*
import forensics.model.Diagnostic
import forensics.model.Snapshot
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class Store(dbFile: File) {
    val mapper: ObjectMapper = jacksonObjectMapper().apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").apply {
        createStatement().execute("PRAGMA journal_mode=WAL")
        createStatement().execute("PRAGMA foreign_keys=ON")
        autoCommit = true
    }

    init {
        migrate()
        recoverPendingWrites()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS cases(
                case_id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at_basis TEXT NOT NULL,
                snapshot_order TEXT NOT NULL)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS materials(
                material_id TEXT PRIMARY KEY, case_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                payload_json TEXT NOT NULL, parser_version TEXT NOT NULL, created_seq INTEGER NOT NULL)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                event_id TEXT PRIMARY KEY, case_id TEXT NOT NULL, seq INTEGER NOT NULL,
                type TEXT NOT NULL, payload_json TEXT NOT NULL)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                result_id TEXT PRIMARY KEY, case_id TEXT NOT NULL, kind TEXT NOT NULL,
                snapshot_id TEXT, param_version INTEGER NOT NULL, payload_json TEXT NOT NULL)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS decision_state(
                case_id TEXT NOT NULL, target_stable_id TEXT NOT NULL, state TEXT NOT NULL,
                updated_seq INTEGER NOT NULL, PRIMARY KEY(case_id, target_stable_id))""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS write_log(
                tx_id TEXT PRIMARY KEY, case_id TEXT NOT NULL, stage TEXT NOT NULL,
                payload_json TEXT NOT NULL, created_at TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0)""".trimIndent()
            )
        }
    }

    @Synchronized
    fun writeCase(header: CaseHeader, firstSnapshot: Snapshot, event: MaterialImported) {
        beginTx("case:${header.caseId}", mapper.writeValueAsString(listOf(header, firstSnapshot, event)))
        conn.autoCommit = false
        try {
            conn.prepareStatement("INSERT OR IGNORE INTO cases VALUES(?,?,?,?)").use { ps ->
                ps.setString(1, header.caseId); ps.setString(2, header.title)
                ps.setString(3, header.createdAtBasis); ps.setString(4, mapper.writeValueAsString(header.snapshotOrder))
                ps.executeUpdate()
            }
            insertMaterial(header.caseId, firstSnapshot, event.seq)
            insertEvent(event)
            conn.commit()
            markTxComplete("case:${header.caseId}")
            conn.autoCommit = true
        } catch (e: Exception) {
            conn.rollback(); conn.autoCommit = true; throw e
        }
    }

    @Synchronized
    fun appendSnapshot(caseId: String, snapshot: Snapshot, event: MaterialImported) {
        val tx = "material:${event.eventId}"
        beginTx(tx, mapper.writeValueAsString(listOf(caseId, snapshot, event)))
        val existingOrder = conn.createStatement().use { st ->
            st.executeQuery("SELECT snapshot_order FROM cases WHERE case_id='$caseId'").use { rs ->
                rs.next(); mapper.readValue<List<String>>(rs.getString(1))
            }
        }
        val newOrderJson = mapper.writeValueAsString(existingOrder + snapshot.snapshotId).replace("'", "''")
        conn.autoCommit = false
        try {
            insertMaterial(caseId, snapshot, event.seq)
            insertEvent(event)
            conn.createStatement().use { st ->
                st.executeUpdate("UPDATE cases SET snapshot_order='$newOrderJson' WHERE case_id='$caseId'")
            }
            conn.commit(); markTxComplete(tx); conn.autoCommit = true
        } catch (e: Exception) {
            conn.rollback(); conn.autoCommit = true; throw e
        }
    }

    @Synchronized
    fun appendEvent(event: CaseEvent) {
        val tx = "event:${event.eventId}"
        beginTx(tx, mapper.writeValueAsString(event))
        conn.autoCommit = false
        try {
            insertEvent(event)
            conn.commit(); markTxComplete(tx); conn.autoCommit = true
        } catch (e: Exception) {
            conn.rollback(); conn.autoCommit = true; throw e
        }
    }

    @Synchronized
    fun saveResult(caseId: String, resultId: String, kind: String, snapshotId: String?,
                   paramVersion: Int, payload: Any) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO results VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, resultId); ps.setString(2, caseId); ps.setString(3, kind)
            ps.setString(4, snapshotId); ps.setInt(5, paramVersion)
            ps.setString(6, mapper.writeValueAsString(payload)); ps.executeUpdate()
        }
    }

    fun resultPayload(resultId: String): String? =
        conn.createStatement().executeQuery("SELECT payload_json FROM results WHERE result_id='$resultId'").use { rs ->
            if (rs.next()) rs.getString(1) else null
        }

    private fun insertMaterial(caseId: String, snapshot: Snapshot, seq: Int) {
        conn.prepareStatement("INSERT OR REPLACE INTO materials VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, "mat-${snapshot.snapshotId}"); ps.setString(2, caseId)
            ps.setString(3, snapshot.snapshotId); ps.setString(4, mapper.writeValueAsString(snapshot))
            ps.setString(5, snapshot.parserVersion); ps.setInt(6, seq); ps.executeUpdate()
        }
    }

    private fun insertEvent(event: CaseEvent) {
        val type = when (event) {
            is MaterialImported -> "MaterialImported"
            is ParamsRevised -> "ParamsRevised"
            is DerivationComputed -> "DerivationComputed"
            is DecisionRecorded -> "DecisionRecorded"
            is CounterexampleRecorded -> "CounterexampleRecorded"
        }
        conn.prepareStatement("INSERT OR REPLACE INTO events VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, event.eventId); ps.setString(2, event.caseId)
            ps.setInt(3, event.seq); ps.setString(4, type)
            ps.setString(5, mapper.writeValueAsString(event)); ps.executeUpdate()
        }
        if (event is DecisionRecorded) {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO decision_state(case_id,target_stable_id,state,updated_seq) VALUES(?,?,?,?)"
            ).use { ps ->
                ps.setString(1, event.caseId); ps.setString(2, event.targetStableId)
                ps.setString(3, event.decision); ps.setInt(4, event.seq); ps.executeUpdate()
            }
        }
    }

    fun caseExists(caseId: String): Boolean =
        conn.createStatement().executeQuery("SELECT 1 FROM cases WHERE case_id='$caseId'").use { it.next() }

    fun listCases(): List<CaseHeader> =
        conn.createStatement().executeQuery("SELECT * FROM cases").use { rs ->
            val out = mutableListOf<CaseHeader>()
            while (rs.next()) out += CaseHeader(rs.getString(1), rs.getString(2), rs.getString(3),
                mapper.readValue(rs.getString(4)))
            out
        }

    fun snapshotOrder(caseId: String): List<String> =
        conn.createStatement().executeQuery("SELECT snapshot_order FROM cases WHERE case_id='$caseId'").use { rs ->
            if (rs.next()) mapper.readValue(rs.getString(1)) else emptyList()
        }

    fun snapshots(caseId: String): List<Snapshot> =
        conn.createStatement()
            .executeQuery("SELECT payload_json FROM materials WHERE case_id='$caseId' ORDER BY created_seq").use { rs ->
                val out = mutableListOf<Snapshot>()
                while (rs.next()) out.add(mapper.readValue(rs.getString(1), Snapshot::class.java))
                out
            }

    fun events(caseId: String): List<CaseEvent> =
        conn.createStatement()
            .executeQuery("SELECT type,payload_json FROM events WHERE case_id='$caseId' ORDER BY seq").use { rs ->
                val out = mutableListOf<CaseEvent>()
                while (rs.next()) out.add(decodeEvent(rs.getString(1), rs.getString(2)))
                out
            }

    fun decisions(caseId: String): Map<String, String> =
        conn.createStatement().executeQuery("SELECT target_stable_id,state FROM decision_state WHERE case_id='$caseId'")
            .use { rs ->
                val m = LinkedHashMap<String, String>()
                while (rs.next()) m[rs.getString(1)] = rs.getString(2)
                m
            }

    fun decodeEvent(type: String, json: String): CaseEvent = when (type) {
        "MaterialImported" -> mapper.readValue<MaterialImported>(json)
        "ParamsRevised" -> mapper.readValue<ParamsRevised>(json)
        "DerivationComputed" -> mapper.readValue<DerivationComputed>(json)
        "DecisionRecorded" -> mapper.readValue<DecisionRecorded>(json)
        "CounterexampleRecorded" -> mapper.readValue<CounterexampleRecorded>(json)
        else -> error("未知事件类型 $type")
    }

    // —— 半写恢复：write_log 记录意图事务，未 completed 的在启动时回滚报告 ——
    private fun beginTx(txId: String, payload: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO write_log VALUES(?,?,?,?,?,0)").use { ps ->
            ps.setString(1, txId); ps.setString(2, txId.substringBefore(':'))
            ps.setString(3, "PENDING"); ps.setString(4, payload)
            ps.setString(5, Instant.now().toString()); ps.executeUpdate()
        }
    }

    private fun markTxComplete(txId: String) {
        conn.createStatement().use { it.executeUpdate("UPDATE write_log SET completed=1 WHERE tx_id='${txId.replace("'", "''")}'") }
    }

    fun pendingWrites(): List<Triple<String, String, String>> =
        conn.createStatement().executeQuery("SELECT tx_id, stage, payload_json FROM write_log WHERE completed=0").use { rs ->
            val l = mutableListOf<Triple<String, String, String>>()
            while (rs.next()) l += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            l
        }

    private fun recoverPendingWrites() {
        // SQLite 事务在崩溃时自动回滚；write_log 中残留的 PENDING 即半写证据，标记为 ROLLED_BACK。
        conn.createStatement().use { it.executeUpdate("UPDATE write_log SET stage='ROLLED_BACK' WHERE completed=0") }
    }

    fun close() = conn.close()
}
