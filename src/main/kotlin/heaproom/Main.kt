package heaproom

import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5366
    var dbPath = "data/heaproom.db"
    var caseDir = "casepack"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
            "--casepack" -> { caseDir = args[++i] }
        }
        i++
    }
    File(dbPath).absoluteFile.parentFile?.mkdirs()
    val storage = Storage(dbPath)
    storage.init()
    CasePack.ensureLoaded(storage, File(caseDir))
    println("堆影取证室已就绪：http://$host:$port （数据库 $dbPath）")
    HeapApp(storage).start(host, port)
}
