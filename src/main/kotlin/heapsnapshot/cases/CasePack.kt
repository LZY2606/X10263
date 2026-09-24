package heapsnapshot.cases

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 离线案例包：专门针对“堆影取证室”，不是通用表格。 */
@Serializable
data class CasePack(
    val packageName: String,
    val title: String,
    val description: String,
    val baselineSnapshotFile: String,
    val secondSnapshotFile: String,
    val scenarios: List<BoundaryScenario>
)

@Serializable
data class BoundaryScenario(
    val caseId: String,
    val branch: String,
    val directSource: String,
    val mutationKind: String,
    val description: String,
    val inputFragment: JsonObject,
    val affectedObjectIds: List<String>,
    val scopeClaim: String,
    val adoptedPolicyVersion: String,
    val rejectedPolicyVersion: String? = null,
    val principle: String? = null
)
