package heapforensics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 离线案例包：从文件系统 casepack/ 或 classpath casepack/ 读取并回放脚本。 */
object Casepack {
    private val json = Json { ignoreUnknownKeys = true }

    fun readFile(name: String): String? {
        val f = File("casepack/$name")
        if (f.exists()) return f.readText()
        val res = Casepack::class.java.classLoader.getResource("casepack/$name")
            ?: Casepack::class.java.classLoader.getResource(name)
        return res?.readText()
    }

    fun available(): Boolean = readFile("case.json") != null

    /** 回放案例脚本：导入 -> 参数修订 -> 局部补丁 -> 冲突决定，全部留事件。 */
    fun replay(service: ForensicsService) {
        val manifest = readFile("case.json") ?: return
        val m = json.parseToJsonElement(manifest).jsonObject
        for (step in m["script"]!!.jsonArray) {
            val s = step.jsonObject
            when (s["op"]!!.jsonPrimitive.content) {
                "import" -> {
                    val raw = readFile(s["file"]!!.jsonPrimitive.content)!!
                    service.importSnapshot(raw, s["source"]!!.jsonPrimitive.content)
                }
                "params" -> service.setParams(
                    WeakPolicy.valueOf(s["weakPolicy"]!!.jsonPrimitive.content),
                    s["maxRootPaths"]!!.jsonPrimitive.content.toInt(),
                    s["rationale"]!!.jsonPrimitive.content,
                )
                "patch" -> {
                    val raw = readFile(s["file"]!!.jsonPrimitive.content)!!
                    service.applyPatch(raw)
                }
                "decide" -> service.decide(
                    s["target"]!!.jsonPrimitive.content,
                    s["verdict"]!!.jsonPrimitive.content,
                    s["rationale"]!!.jsonPrimitive.content,
                )
            }
        }
    }
}
