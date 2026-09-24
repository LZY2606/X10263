package heapforensics

import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5366
    var dbPath = "data/heapforensics.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
        }
        i++
    }
    File(dbPath).absoluteFile.parentFile?.mkdirs()
    Store(dbPath).use { store ->
        require(store.integrityOk()) { "database integrity check failed" }
        CasePack.seed(store)
        HeapForensicsServer(store).start(host, port)
    }
}
