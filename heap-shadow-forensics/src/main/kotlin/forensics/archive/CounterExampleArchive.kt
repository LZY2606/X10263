package forensics.archive

import forensics.graph.GraphAnalysis
import forensics.graph.GraphEngine
import forensics.graph.WeakPolicy
import forensics.model.HeapSnapshot
import forensics.parse.HsfParser
import forensics.parse.IngestResult
import forensics.parse.IngestSemantics
import kotlinx.serialization.Serializable

/**
 * Every counter-example mutates exactly one direct source relative to the baseline.
 * Expected impact scopes are declared up front; both adopted and rejected judgments are retained.
 */
@Serializable
data class CounterExampleFinding(
    val id: String,
    val family: String,
    val description: String,
    val mutation: String,
    val expectedAffectedStableIds: List<String>,
    val actualAffectedStableIds: List<String>,
    val scopeIsolated: Boolean,
    val adopted: Boolean,
    val rationale: String,
    val outcome: String,
)

object CounterExampleArchive {

    const val CLAIM_DOM_REACHABLE =
        "dominator tree is built only on the reachable graph; weak keep-alive is an explicit policy"
    const val CLAIM_TIES_KEPT =
        "multiple equal-length root paths are all retained; address is not a stable identity"

    fun affect(a: GraphAnalysis, b: GraphAnalysis): List<String> {
        val va = a.objects.associateBy { it.stableId }
        val out = mutableListOf<String>()
        b.objects.forEach { ob ->
            val oa = va[ob.stableId]
            if (oa == null || oa.keptAlive != ob.keptAlive ||
                oa.dominatorStableId != ob.dominatorStableId ||
                oa.retained.bytes != ob.retained.bytes ||
                oa.rootPaths.map { it.objectIds }.toSet() != ob.rootPaths.map { it.objectIds }.toSet() ||
                oa.leakCandidate != ob.leakCandidate
            ) out += ob.stableId
        }
        return out.sorted()
    }

    data class Variant(
        val id: String,
        val family: String,
        val description: String,
        val mutatedText: String,
        val expected: (HeapSnapshot, HeapSnapshot) -> List<String>,
        val adopted: Boolean,
        val rationale: String,
    )

    fun mutate(baselineText: String, family: String): String = when (family) {
        "cycle" -> baselineText.replace("EDGE o2;o3;STRONG;field;next", "EDGE o2;o1;STRONG;field;cycleBack")
        "weak" -> baselineText.replace("EDGE o6;o7;WEAK;ref;cache", "EDGE o6;o7;STRONG;ref;cachePromoted")
        "dup-address" -> baselineText.replace("OBJECT o9;0x9000;cls1;512B", "OBJECT o9;0x3000;cls1;512B")
        "incomplete" -> baselineText.replace("END BLOCK-A", "END BLOCK-Z")
        "multi-root" -> baselineText.replace("EDGE o1;o4;STRONG;field;link", "EDGE o8;o4;STRONG;field;secondEqualLink")
        "rename" -> baselineText.replace("CLASS cls1;HashMap;loader1", "CLASS cls1;LinkedHashMap;loader1;HashMap")
        else -> error("unknown family $family")
    }

    private fun expectedScope(family: String): (HeapSnapshot, HeapSnapshot) -> List<String> = { base, variant ->
        when (family) {
            "cycle" -> {
                val ids = linkedSetOf<String>()
                base.objectByObjectId("o2")?.stableId?.let { ids += it }
                base.objectByObjectId("o3")?.stableId?.let { ids += it }
                variant.objects.firstOrNull { it.objectId == "o2" }?.stableId?.let { ids += it }
                ids.sorted()
            }
            "weak" -> variant.objects.filter { it.objectId in setOf("o7", "o8") }.map { it.stableId }.sorted()
            "dup-address" -> emptyList() // address change must NOT change any graph verdict
            "incomplete" -> emptyList() // block bookkeeping only, no graph verdict change
            "multi-root" -> variant.objects.filter { it.objectId == "o4" }.map { it.stableId }
            "rename" -> emptyList() // identity correspondence preserved; graph verdicts untouched
            else -> emptyList()
        }
    }

    fun variants(baselineText: String): List<Variant> = listOf(
        Variant("ce-cycle", "cycle", "introduce o2->o1 back edge (cycle graph)",
            mutate(baselineText, "cycle"), expectedScope("cycle"), true,
            "cycles are traversed without infinite expansion; dominator result stays finite"),
        Variant("ce-weak-policy", "weak", "promote the WEAK cache edge to STRONG in a variant",
            mutate(baselineText, "weak"), expectedScope("weak"), true,
            CLAIM_DOM_REACHABLE),
        Variant("ce-dup-address", "dup-address", "give o9 the same address as o3",
            mutate(baselineText, "dup-address"), expectedScope("dup-address"), true,
            CLAIM_TIES_KEPT),
        Variant("ce-incomplete", "incomplete", "mismatch END so BLOCK-A stays incomplete",
            mutate(baselineText, "incomplete"), expectedScope("incomplete"), true,
            "incomplete block is retained verbatim with position; it never fabricates objects"),
        Variant("ce-multi-root", "multi-root", "add a second equal-length root route to o4",
            mutate(baselineText, "multi-root"), expectedScope("multi-root"), true,
            "all equal-length root paths survive; dominator becomes the super root when tied"),
        Variant("ce-rename", "rename", "rename cls1 HashMap -> LinkedHashMap with renamedFrom",
            mutate(baselineText, "rename"), expectedScope("rename"), true,
            "classes correspond by stableId; a rename does not split the class group"),
        Variant("ce-rejected", "weak", "claim that a weak edge keeps o8 alive under weak-v1 (REJECTED)",
            baselineText, { _, v -> v.objects.filter { it.objectId == "o8" }.map { it.stableId } }, false,
            "rejected: weak-v1 explicitly does not keep weak targets alive; evidence kept for audit"),
    )

    fun runVariant(baseline: HeapSnapshot, baselineAnalysis: GraphAnalysis, variant: Variant, policy: WeakPolicy): CounterExampleFinding {
        val outcome: String
        val variantSnap: HeapSnapshot
        val po = HsfParser().parse(variant.mutatedText)
        when (po) {
            is forensics.parse.ParseOutcome.Ok -> { variantSnap = po.snapshot; outcome = "OK" }
            is forensics.parse.ParseOutcome.Corrupt -> return CounterExampleFinding(
                variant.id, variant.family, variant.description, variant.mutation,
                emptyList(), emptyList(), false, false, variant.rationale,
                "CORRUPT@${po.diagnostic.position.line}: ${po.diagnostic.message}")
            is forensics.parse.ParseOutcome.BudgetExhausted -> return CounterExampleFinding(
                variant.id, variant.family, variant.description, variant.mutation,
                emptyList(), emptyList(), false, false, variant.rationale, "BUDGET_EXHAUSTED")
        }
        val sem = IngestSemantics.validate(variantSnap)
        if (sem is IngestResult.SemanticImpossible) return CounterExampleFinding(
            variant.id, variant.family, variant.description, variant.mutation,
            emptyList(), emptyList(), false, variant.adopted, variant.rationale,
            "SEMANTIC: " + sem.violations.joinToString(";") { "${it.code}@${it.position.line}" })
        val va = GraphEngine(variantSnap, policy).analyze()
        val expected = variant.expected(baseline, variantSnap).sorted()
        val actual = affect(baselineAnalysis, va)
        val isolated = actual.toSet() == expected.toSet()
        val adopted = variant.adopted && (if (variant.id == "ce-rejected") !isolated else isolated)
        return CounterExampleFinding(
            variant.id, variant.family, variant.description, variant.mutation,
            expected, actual, isolated, adopted,
            if (variant.id == "ce-rejected")
                variant.rationale + " (finding adopted=${isolated.not()})"
            else variant.rationale + if (isolated) "" else " — impact spread beyond expected scope",
            outcome,
        )
    }
}
