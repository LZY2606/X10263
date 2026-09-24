package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.service.ReviewService
import heapforensics.store.Database
import heapforensics.web.startServer
import java.nio.file.Paths

/**
 * 堆影取证室入口。默认直接打开带离线样例的审阅页：
 *   ./gradlew run --args='--host 127.0.0.1 --port 5366'
 */
fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5366
    var dbPath = "data/heapforensics.db"
    var noSeed = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--db" -> dbPath = args[++i]
            "--no-seed" -> noSeed = true
            "--help", "-h" -> {
                println("Usage: --host 127.0.0.1 --port 5366 [--db data/heapforensics.db] [--no-seed]")
                return
            }
            else -> System.err.println("忽略未知参数: ${args[i]}")
        }
        i++
    }
    val db = Database.open(Paths.get(dbPath))
    val service = ReviewService(db)
    if (!noSeed) {
        val report = CasePack.seed(service)
        if (!report.alreadySeeded) println("已播种离线案例包：${report.executed} 个叙事步骤")
    }
    println("堆影取证室: http://$host:$port")
    startServer(service, host, port)
}
