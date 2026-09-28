package forensics.heapsnapshot.storage

import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class Database(path: String) {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        connection.autoCommit = false
        connection.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS projects(
                     id TEXT PRIMARY KEY, name TEXT NOT NULL, case_package TEXT,
                     created_at TEXT NOT NULL, tip_event_id TEXT)""".trimIndent(),
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS snapshots(
                     project_id TEXT NOT NULL, snapshot_id TEXT PRIMARY KEY,
                     identity TEXT NOT NULL, raw TEXT NOT NULL, import_seq INTEGER NOT NULL)""".trimIndent(),
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     project_id TEXT NOT NULL, event_id TEXT PRIMARY KEY,
                     seq INTEGER NOT NULL, parent_event_id TEXT, branch TEXT NOT NULL,
                     at TEXT NOT NULL, actor TEXT NOT NULL, type TEXT NOT NULL,
                     payload_json TEXT NOT NULL)""".trimIndent(),
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS analysis_blobs(
                     project_id TEXT NOT NULL, event_id TEXT PRIMARY KEY,
                     snapshot_id TEXT NOT NULL, params_version INTEGER NOT NULL,
                     result_json TEXT NOT NULL)""".trimIndent(),
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS diff_blobs(
                     project_id TEXT NOT NULL, id TEXT PRIMARY KEY,
                     before_id TEXT NOT NULL, after_id TEXT NOT NULL,
                     result_json TEXT NOT NULL)""".trimIndent(),
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS write_journal(
                     tx_id TEXT PRIMARY KEY, project_id TEXT NOT NULL,
                     stage TEXT NOT NULL, created_at TEXT NOT NULL)""".trimIndent(),
            )
        }
        connection.commit()
    }

    fun close() = connection.close()

    fun deleteProjectFiles(workDir: File) {
        // 保留数据库文件本身；项目数据通过 API 删除。
    }
}
