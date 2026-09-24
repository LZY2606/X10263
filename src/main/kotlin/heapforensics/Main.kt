package heapforensics

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5366
    var db = "data/heapforensics.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--db" -> db = args[++i]
        }
        i++
    }
    val store = Store(db)
    val service = ForensicsService(store)
    if (store.isEmpty() && Casepack.available()) {
        Casepack.replay(service)
    }
    println("堆影取证室 listening on http://$host:$port (db=$db)")
    embeddedServer(Netty, port = port, host = host) {
        webModule(service)
    }.start(wait = true)
}
