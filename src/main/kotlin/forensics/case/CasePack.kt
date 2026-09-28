package forensics.case

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 离线领域案例包："堆影取证室"。从 classpath 或资源目录加载，不依赖云端。 */
class CasePack(private val readResource: (String) -> String?) {

    private val mapper = jacksonObjectMapper()

    fun text(name: String): String =
        readResource(name) ?: error("案例包缺少资源 $name")

    fun manifest(): JsonNode = mapper.readTree(text("manifest.json"))

    val baseline get() = text("baseline.json")
    val snapshotB get() = text("snapshot-B.json")
    val classRename get() = text("cx-class-rename.json")

    fun variant(scenario: String): String = text("cx-${scenario}.json")

    val allScenarios = listOf(
        "cycle", "weak-policy", "duplicate-address", "incomplete-object",
        "equal-root-paths", "class-rename", "snapshot-identity",
    )

    companion object {
        fun fromClasspath(): CasePack {
            val cl = CasePack::class.java.classLoader
            return CasePack { name ->
                val n = if (name.contains('/')) name else "cases/$name"
                cl.getResourceAsStream(n)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }
        }
    }
}
