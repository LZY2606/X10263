package forensics

import forensics.web.startServer
import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"; var port = 5366
    var db = File(System.getProperty("heapshadow.db", "heap-shadow.db"))
    val it = args.iterator()
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--host" -> host = it.next()
            "--port" -> port = it.next().toInt()
            "--db" -> db = File(it.next())
            else -> error("未知参数 $a")
        }
    }
    println("堆影取证室启动中: http://$host:$port （数据库 ${db.absolutePath}）")
    startServer(host, port, db)
}
