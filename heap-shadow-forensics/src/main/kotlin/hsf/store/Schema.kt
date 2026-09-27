package hsf.store

internal object Schema {
    const val CREATE = """
    CREATE TABLE IF NOT EXISTS cases(
        case_id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        head_seq INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS branches(
        case_id TEXT NOT NULL,
        branch TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY(case_id, branch)
    );

    CREATE TABLE IF NOT EXISTS events(
        case_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        branch TEXT NOT NULL,
        event_id TEXT PRIMARY KEY,
        type TEXT NOT NULL,
        payload TEXT NOT NULL,
        at_millis INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_events_case_branch ON events(case_id, branch, seq);

    CREATE TABLE IF NOT EXISTS materials(
        case_id TEXT NOT NULL,
        snapshot_id TEXT NOT NULL,
        material_hash TEXT NOT NULL,
        source_name TEXT NOT NULL,
        source_text TEXT NOT NULL,
        imported_at INTEGER NOT NULL,
        PRIMARY KEY(case_id, snapshot_id, material_hash)
    );

    CREATE TABLE IF NOT EXISTS results(
        case_id TEXT NOT NULL,
        result_id TEXT NOT NULL,
        branch TEXT NOT NULL,
        params_version TEXT NOT NULL,
        parent_result_id TEXT,
        payload TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY(case_id, result_id)
    );

    CREATE TABLE IF NOT EXISTS archives(
        case_id TEXT PRIMARY KEY,
        payload TEXT NOT NULL,
        created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS import_journal(
        journal_id TEXT PRIMARY KEY,
        case_id TEXT NOT NULL,
        stage TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        finished_at INTEGER
    );

    CREATE TABLE IF NOT EXISTS kv_meta(
        k TEXT PRIMARY KEY,
        v TEXT NOT NULL
    );
    """
}
