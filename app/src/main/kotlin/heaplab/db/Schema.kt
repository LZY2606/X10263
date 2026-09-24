package heaplab.db

import java.sql.Connection

object Schema {
    const val VERSION = 1

    fun migrate(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""".trimIndent()
            )
            st.executeUpdate("INSERT OR IGNORE INTO meta(key,value) VALUES ('schema_version','$VERSION')")

            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS snapshot (
                    snapshot_id TEXT PRIMARY KEY,
                    source_name TEXT NOT NULL,
                    format_version TEXT NOT NULL,
                    parser_version TEXT NOT NULL,
                    imported_at INTEGER NOT NULL,
                    origin TEXT NOT NULL,
                    raw_json TEXT NOT NULL,
                    content_hash TEXT NOT NULL
                )""".trimIndent()
            )

            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS analysis_run (
                    run_id TEXT PRIMARY KEY,
                    snapshot_id TEXT NOT NULL,
                    parent_run_id TEXT,
                    parameters_version INTEGER NOT NULL,
                    parameters_json TEXT NOT NULL,
                    input_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    outcome_json TEXT,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(snapshot_id) REFERENCES snapshot(snapshot_id)
                )""".trimIndent()
            )

            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS event (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id TEXT UNIQUE NOT NULL,
                    type TEXT NOT NULL,
                    epoch_millis INTEGER NOT NULL,
                    snapshot_id TEXT,
                    run_id TEXT,
                    parent_event_seq INTEGER,
                    parent_run_id TEXT,
                    parameters_version INTEGER,
                    input_content_hash TEXT,
                    actor TEXT NOT NULL,
                    label TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                )""".trimIndent()
            )

            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS import_lock (
                    lock_id TEXT PRIMARY KEY,
                    stage TEXT NOT NULL,
                    started_at INTEGER NOT NULL
                )""".trimIndent()
            )

            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS counterexample (
                    archive_id TEXT NOT NULL,
                    scenario_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    baseline_snapshot_id TEXT,
                    variant_snapshot_id TEXT,
                    expected_scope_json TEXT NOT NULL,
                    adopted_json TEXT NOT NULL,
                    rejected_json TEXT NOT NULL,
                    rationale_event TEXT NOT NULL,
                    PRIMARY KEY(archive_id, scenario_key)
                )""".trimIndent()
            )

            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_run_snapshot ON analysis_run(snapshot_id)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_event_snapshot ON event(snapshot_id)")
        }
    }
}
