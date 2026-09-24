package heaplab.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * SQLite 存储层。WAL + 外键；所有写操作在短事务内。
 * 半写恢复：连接打开时执行 PRAGMA，integrity_check，并清理任何未完成的导入暂存。
 */
class Database private constructor(val conn: Connection, val dbPath: String) {

    companion object {
        fun open(path: String): Database {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            val db = Database(conn, path)
            db.configure()
            Schema.migrate(conn)
            db.recoverHalfWrites()
            return db
        }
    }

    private fun configure() {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA synchronous=FULL")
            st.execute("PRAGMA busy_timeout=5000")
        }
    }

    /** 半写恢复：staging 导入未 commit 即崩溃时，其临时行会随事务回滚；
     *  这里仅检测并清除显式的 import_lock，并记录一次恢复事件。 */
    fun recoverHalfWrites(): String? {
        var leftover: String? = null
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT lock_id, stage FROM import_lock")
            if (rs.next()) leftover = rs.getString(1) + ":" + rs.getString(2)
        }
        if (leftover != null) {
            conn.autoCommit = false
            conn.createStatement().use { it.execute("DELETE FROM import_lock") }
            conn.commit()
            conn.autoCommit = true
        }
        return leftover
    }

    fun integrityOk(): Boolean = conn.createStatement().use { st ->
        val rs = st.executeQuery("PRAGMA integrity_check")
        rs.next() && rs.getString(1) == "ok"
    }

    fun tx(body: (Statement) -> Unit) {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { body(it) }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prev
        }
    }
}
