package com.heaptrace.storage

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * SQLite 存储层：可被测试直接调用。
 * 不可变材料（原始 JSON、解析器版本、不完整块）与可修改决定（事件/决定/判定）分表分层。
 * 启动时执行崩溃恢复检查；所有写入走单事务（导入失败不会留下半写状态）。
 */
class HeapDatabase private constructor(val conn: Connection, val dbPath: String) {

    companion object {
        const val SCHEMA_VERSION = 1

        fun open(path: String): HeapDatabase {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA synchronous=FULL")
            }
            conn.commit()
            val db = HeapDatabase(conn, path)
            db.migrate()
            db.recover()
            return db
        }
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS meta(
                   key TEXT PRIMARY KEY, value TEXT NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS bundles(
                   snapshot_id TEXT PRIMARY KEY,
                   label TEXT NOT NULL,
                   parser_version TEXT NOT NULL,
                   captured_at TEXT NOT NULL,
                   derived_from TEXT,
                   change_note TEXT,
                   raw_json TEXT NOT NULL,
                   import_event_id TEXT NOT NULL,
                   seq INTEGER NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS import_diagnostics(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   snapshot_id TEXT NOT NULL,
                   ord INTEGER NOT NULL,
                   json TEXT NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS events(
                   seq INTEGER PRIMARY KEY,
                   id TEXT NOT NULL UNIQUE,
                   parent_id TEXT,
                   kind TEXT NOT NULL,
                   at TEXT NOT NULL,
                   snapshot_id TEXT,
                   payload_json TEXT NOT NULL,
                   digest TEXT NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS analyses(
                   analysis_id TEXT PRIMARY KEY,
                   snapshot_id TEXT NOT NULL,
                   event_id TEXT NOT NULL,
                   status TEXT NOT NULL,
                   policy_json TEXT NOT NULL,
                   result_json TEXT,
                   seq INTEGER NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS decisions(
                   decision_id TEXT PRIMARY KEY,
                   event_id TEXT NOT NULL,
                   snapshot_id TEXT NOT NULL,
                   kind TEXT NOT NULL,
                   target TEXT,
                   params_json TEXT NOT NULL,
                   reason TEXT NOT NULL,
                   reviewer TEXT NOT NULL,
                   seq INTEGER NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS judgments(
                   judgment_id TEXT PRIMARY KEY,
                   event_id TEXT NOT NULL,
                   scenario_id TEXT NOT NULL,
                   rule_id TEXT NOT NULL,
                   holds INTEGER NOT NULL,
                   adopted INTEGER NOT NULL,
                   expected_json TEXT NOT NULL,
                   observed_json TEXT NOT NULL,
                   evidence_json TEXT NOT NULL,
                   seq INTEGER NOT NULL)"""
            )
            val existing = st.queryString("SELECT value FROM meta WHERE key='schema_version'")
            if (existing == null) {
                st.execute("INSERT INTO meta(key,value) VALUES('schema_version','$SCHEMA_VERSION')")
            }
        }
        conn.commit()
    }

    /** 崩溃后的半写恢复：未提交事务会被 SQLite 回滚；再做完整性检查。 */
    fun recover(): RecoveryReport {
        val integrity = conn.createStatement().executeQuery("PRAGMA integrity_check").use { it.getString(1) }
        val eventCount = conn.createStatement().executeQuery("SELECT COUNT(*) FROM events").use { it.getInt(1) }
        conn.commit()
        val rolledBack = try {
            conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(PASSIVE)") }; false
        } catch (e: Exception) {
            true
        }
        return RecoveryReport(integrity == "ok", integrity, eventCount, rolledBack)
    }

    fun close() = conn.close()
}

data class RecoveryReport(val integrityOk: Boolean, val detail: String, val eventCount: Int, val checkpointRecovered: Boolean)

fun Statement.queryString(sql: String): String? =
    executeQuery(sql).use { if (it.next()) it.getString(1) else null }
