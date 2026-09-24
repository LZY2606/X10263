package heaplab

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5366
    var db = "data/heaplab.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { db = args[++i] }
            else -> System.err.println("未知参数：${args[i]}")
        }
        i++
    }

    Store(Path.of(db)).use { store ->
        Seeder.seed(store)
        embeddedServer(Netty, port = port, host = host) {
            webModule(store)
        }.start(wait = true)
    }
}
