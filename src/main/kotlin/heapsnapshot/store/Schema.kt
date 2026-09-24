package heapsnapshot.store

internal object Schema {
    const val VERSION = 1
    val DDL = listOf(
        """CREATE TABLE IF NOT EXISTS meta(
             key TEXT PRIMARY KEY,
             value TEXT NOT NULL
           )""",
        """CREATE TABLE IF NOT EXISTS projects(
             project_id TEXT PRIMARY KEY,
             name TEXT NOT NULL,
             created_at_ms INTEGER NOT NULL
           )""",
        """CREATE TABLE IF NOT EXISTS materials(
             material_id TEXT PRIMARY KEY,
             project_id TEXT NOT NULL,
             snapshot_id TEXT NOT NULL,
             parser_version TEXT NOT NULL,
             raw_json TEXT NOT NULL,
             content_hash TEXT NOT NULL,
             created_event_id TEXT,
             imported_at_ms INTEGER NOT NULL
           )""",
        """CREATE TABLE IF NOT EXISTS events(
             seq INTEGER PRIMARY KEY AUTOINCREMENT,
             event_id TEXT NOT NULL UNIQUE,
             project_id TEXT NOT NULL,
             parent_event_id TEXT,
             branch TEXT NOT NULL,
             epoch_ms INTEGER NOT NULL,
             payload_json TEXT NOT NULL,
             in_ledger INTEGER NOT NULL DEFAULT 0
           )""",
        """CREATE TABLE IF NOT EXISTS analyses(
             result_id TEXT PRIMARY KEY,
             project_id TEXT NOT NULL,
             snapshot_id TEXT NOT NULL,
             policy_version TEXT NOT NULL,
             created_event_id TEXT NOT NULL,
             result_json TEXT NOT NULL,
             valid_until_event_id TEXT,
             invalidation_reason TEXT
           )""",
        """CREATE TABLE IF NOT EXISTS diffs(
             diff_id TEXT PRIMARY KEY,
             project_id TEXT NOT NULL,
             base_snapshot_id TEXT NOT NULL,
             other_snapshot_id TEXT NOT NULL,
             created_event_id TEXT NOT NULL,
             diff_json TEXT NOT NULL
           )""",
        """CREATE TABLE IF NOT EXISTS write_intent(
             intent_id TEXT PRIMARY KEY,
             project_id TEXT NOT NULL,
             kind TEXT NOT NULL,
             started_at_ms INTEGER NOT NULL,
             staged_json TEXT NOT NULL,
             completed INTEGER NOT NULL DEFAULT 0
           )"""
    )
}
