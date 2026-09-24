package forensics.heapshadow.storage

internal object Schema {
    const val CREATE = """
    CREATE TABLE IF NOT EXISTS meta(
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS branches(
        branch TEXT PRIMARY KEY,
        parent_branch TEXT,
        fork_from_event_id TEXT,
        created_at_ms INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS events(
        event_id TEXT PRIMARY KEY,
        seq INTEGER NOT NULL,
        branch TEXT NOT NULL,
        type TEXT NOT NULL,
        occurred_at_ms INTEGER NOT NULL,
        time_basis TEXT,
        parent_event_ids TEXT NOT NULL,
        parameter_version TEXT,
        input_digest TEXT,
        payload TEXT NOT NULL,
        ref_id TEXT,
        note TEXT
    );
    CREATE TABLE IF NOT EXISTS materials(
        material_id TEXT PRIMARY KEY,
        snapshot_id TEXT NOT NULL,
        event_id TEXT NOT NULL,
        raw_json TEXT NOT NULL,
        parser_version TEXT NOT NULL,
        imported_at_ms INTEGER NOT NULL,
        ordinal INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS analyses(
        analysis_id TEXT PRIMARY KEY,
        snapshot_id TEXT NOT NULL,
        parameter_version TEXT NOT NULL,
        input_digest TEXT NOT NULL,
        parent_event_ids TEXT NOT NULL,
        result_json TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS diffs(
        diff_id TEXT PRIMARY KEY,
        snapshot_id_a TEXT NOT NULL,
        snapshot_id_b TEXT NOT NULL,
        parameter_version TEXT NOT NULL,
        digest_a TEXT NOT NULL,
        digest_b TEXT NOT NULL,
        result_json TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS probe_archives(
        archive_id TEXT PRIMARY KEY,
        probe_key TEXT NOT NULL,
        adopted INTEGER NOT NULL,
        baseline_json TEXT NOT NULL,
        outcome_json TEXT NOT NULL,
        evidence_json TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL
    );
    -- 半写恢复：导入按阶段推进，崩溃后可识别半成品并隔离/回滚。
    CREATE TABLE IF NOT EXISTS import_staging(
        staging_id TEXT PRIMARY KEY,
        snapshot_id TEXT NOT NULL,
        phase TEXT NOT NULL,
        raw_json TEXT,
        created_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_events_branch_seq ON events(branch, seq);
    CREATE INDEX IF NOT EXISTS idx_analyses_lookup ON analyses(snapshot_id, parameter_version, input_digest);
    """
}
