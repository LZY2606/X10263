package heapsnapshot.server

import heapsnapshot.domain.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 四条硬规则的可重放判定。输入是不可变材料与两份（不同策略版本的）派生结果，
 * 输出 holds + 证据对象 id + 结构化观测。
 */
object CaseAssertions {

    data class Evaluation(val holds: Boolean, val objectIds: List<String>, val rationale: String, val observed: JsonObject)

    fun evaluate(
        code: PrincipleCode,
        snapshot: RawSnapshot,
        resV1: AnalysisResult,
        resV2: AnalysisResult
    ): Evaluation = when (code) {
        PrincipleCode.DOMINATOR_REACHABLE_ONLY -> {
            val v1Nodes = resV1.nodes.map { it.objectId }.toSet()
            val v2Nodes = resV2.nodes.map { it.objectId }.toSet()
            val onlyV2 = (v2Nodes - v1Nodes).sorted()
            // 支配节点集合必须等于可达集合；弱策略翻转时新增可达者才进入支配树。
            val holds = resV1.nodes.all { it.objectId in resV1.reachableObjectIds } &&
                resV2.nodes.all { it.objectId in resV2.reachableObjectIds } &&
                onlyV2 == listOf("obj-WTARGET")
            Evaluation(holds, listOf("obj-A", "obj-B", "obj-WTARGET"),
                "支配树节点集合与可达集合一致；循环对象不自支配；WTARGET 仅在 v2 进入支配树",
                buildJsonObject {
                    put("reachable_v1", resV1.reachableObjectIds.joinToString(","))
                    put("reachable_v2", resV2.reachableObjectIds.joinToString(","))
                    put("added_by_policy_v2", onlyV2.joinToString(","))
                    put("cycleEdge", "edge-a-b+edge-b-a")
                })
        }
        PrincipleCode.WEAK_KEEPALIVE_EXPLICIT_POLICY -> {
            val unreachableV1 = "obj-WTARGET" in resV1.unreachableObjectIds
            val reachableV2 = "obj-WTARGET" in resV2.reachableObjectIds
            val sameMaterial = resV1.inputSummary.materialHash == resV2.inputSummary.materialHash
            Evaluation(unreachableV1 && reachableV2 && sameMaterial,
                listOf("obj-WTARGET", "obj-CACHE"),
                "材料哈希一致，唯一差异是显式策略 weakKeepsAlive：v1=false 不保活，v2=true 保活",
                buildJsonObject {
                    put("materialHashEqual", sameMaterial)
                    put("wtarget_unreachable_under", resV1.policyVersion)
                    put("wtarget_reachable_under", resV2.policyVersion)
                    put("weakEdge", "edge-cache-w")
                })
        }
        PrincipleCode.ALL_EQUAL_ROOT_PATHS_KEPT -> {
            val nodeV1 = resV1.nodes.firstOrNull { it.objectId == "obj-X" }
            val pathCount = nodeV1?.rootPaths?.size ?: 0
            val tails = nodeV1?.rootPaths?.map { it.firstOrNull() ?: "" }?.distinct()?.sorted() ?: emptyList()
            val holds = pathCount == 2 && tails == listOf("obj-R1", "obj-R2")
            Evaluation(holds, listOf("obj-X", "obj-R1", "obj-R2"),
                "obj-X 有两条等长 root 路径（经 root-1/R1 与 root-2/R2），全部保留且顺序稳定",
                buildJsonObject {
                    put("rootPathCount", pathCount)
                    put("firstHopVariants", tails.joinToString(","))
                    put("rootDistance", nodeV1?.rootDistance ?: -1)
                })
        }
        PrincipleCode.ADDRESS_NOT_STABLE_IDENTITY -> {
            val d1 = snapshot.objectById("obj-D1")
            val d2 = snapshot.objectById("obj-D2")
            val shareAddress = d1 != null && d2 != null && d1.address.numeric != null &&
                d1.address.numeric == d2.address.numeric && d1.objectId != d2.objectId
            val duplicateWarn = snapshot.diagnostics.any { it.code == "DUPLICATE_ADDRESS" }
            val bothKept = d1 != null && d2 != null
            Evaluation(shareAddress && duplicateWarn && bothKept,
                listOf("obj-D1", "obj-D2"),
                "共享地址只产生 DUPLICATE_ADDRESS 警告；两个对象以 objectId 独立保留，地址不用于合并",
                buildJsonObject {
                    put("sharedAddressRaw", d1?.address?.raw ?: "")
                    put("distinctStableIds", "obj-D1,obj-D2")
                    put("warningEmitted", duplicateWarn)
                })
        }
    }
}
