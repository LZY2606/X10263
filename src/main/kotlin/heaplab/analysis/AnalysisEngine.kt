package heaplab.analysis

import heaplab.model.*

/**
 * 业务规则全部在服务端/存储层可直接调用，不依赖浏览器。
 *
 * 三种互斥结果：
 *  - 正常：返回 AnalysisResult；
 *  - 资源预算耗尽：BudgetExhaustedException；
 *  - 格式损坏：CorruptFormatException（解析阶段，带位置）；
 *  - 语义无解：SemanticallyAmbiguousException（如重复原始对象 id 导致无法唯一裁决）。
 */
class AnalysisEngine {

    fun analyze(
        snapshot: Snapshot,
        params: AnalysisParams,
        parentDependencies: List<String> = emptyList(),
        invalidationReasons: List<String> = emptyList()
    ): AnalysisResult {
        // 语义无解 1：同一快照内重复 rawObjectId（材料层已保留两条，但图身份无法唯一决定）。
        val dupIds = snapshot.objects.groupingBy { it.rawObjectId }.eachCount().filterValues { it > 1 }
        if (dupIds.isNotEmpty()) {
            throw SemanticallyAmbiguousException(
                "重复的原始对象 id 使对象图身份无法唯一决定",
                candidates = dupIds.keys.sorted(),
                objectIds = dupIds.keys.toList()
            )
        }

        val reach = Reachability.compute(snapshot, params)

        // 只在可达图上构建支配树：加入虚拟 SUPER_ROOT 直连所有 GC root。
        val superRoot = "SUPER_ROOT@${snapshot.snapshotId}"
        val succ = LinkedHashMap<String, List<String>>()
        for ((u, outs) in reach.adjacency) {
            if (u in reach.reachable) succ[u] = outs.map { it.to }
        }
        succ[superRoot] = reach.roots
        val dom = Dominators.build(superRoot, succ)

        val shallow = snapshot.objects.associate { it.rawObjectId to (it.sizeBytes ?: 0L) }
        val retained = dom.retainedSizes(shallow)

        // 每个可达对象的全部等长 root 路径（预算内）。只对可达对象计算。
        val pathsByTarget = LinkedHashMap<String, List<RootPath>>()
        for (o in snapshot.objects) {
            if (o.rawObjectId in reach.reachable) {
                pathsByTarget[o.rawObjectId] =
                    RootPaths.allShortest(o.rawObjectId, reach, params.maxPathCandidates)
            }
        }

        val diagByObj = snapshot.diagnostics.groupBy { it.objectId }
        val keyOf = { id: String -> "${snapshot.snapshotId}#$id" }

        val objectViews = snapshot.objects.map { o ->
            val className = snapshot.classById[o.classId]?.nameRaw ?: "<缺失类:${o.classId}>"
            val site = o.allocationSiteId?.let { snapshot.siteById[it] }
            val domParent = dom.parent(o.rawObjectId)?.takeUnless { it == superRoot }?.let(keyOf)
            ObjectView(
                nodeKey = keyOf(o.rawObjectId),
                snapshotId = snapshot.snapshotId,
                rawObjectId = o.rawObjectId,
                correspondenceKey = o.correspondenceKey,
                addressRaw = o.addressRaw,
                classId = o.classId,
                className = className,
                allocationSiteId = o.allocationSiteId,
                allocationLocationRaw = site?.locationRaw,
                rootKind = o.rootKind,
                shallowSizeRaw = o.sizeRaw,
                shallowSizeBytes = o.sizeBytes,
                sizeUnit = snapshot.coordinate.sizeUnit,
                incomplete = o.incomplete,
                incompleteChunkId = o.incompleteChunkId,
                reachable = o.rawObjectId in reach.reachable,
                rootDistance = reach.distance[o.rawObjectId],
                dominatorParentKey = domParent,
                retainedSizeBytes = retained[o.rawObjectId] ?: 0L,
                rootPathCount = pathsByTarget[o.rawObjectId]?.size ?: 0,
                diagnostics = diagByObj[o.rawObjectId].orEmpty().map { it.code }
            )
        }.sortedWith(compareByDescending<ObjectView> { it.retainedSizeBytes }.thenBy { it.nodeKey })

        val excludedSet = reach.excludedEdges.toSet()
        val edgeViews = snapshot.edges.map { e ->
            GraphEdgeView(
                edgeId = e.edgeId,
                fromKey = keyOf(e.fromObjectId),
                toKey = keyOf(e.toObjectId),
                kind = e.kind,
                fieldRaw = e.fieldRaw,
                participatingInReachability = e.edgeId !in excludedSet &&
                    e.fromObjectId in reach.reachable && e.toObjectId in reach.reachable
            )
        }.sortedWith(compareBy({ it.fromKey }, { it.toKey }, { it.edgeId }))

        // 泄漏路径候选：可达、非 root、支配父级为 SUPER_ROOT（root 直接支配簇）
        // 或被不完整对象支配的较大保留簇。全部 root 路径随附。
        val leakCandidates = snapshot.objects
            .filter { it.rawObjectId in reach.reachable && it.rawObjectId !in reach.roots }
            .map { o ->
                val domParentRaw = dom.parent(o.rawObjectId)
                Triple(o, domParentRaw, retained[o.rawObjectId] ?: 0L)
            }
            .filter { (o, parentRaw, _) ->
                parentRaw == superRoot ||
                    (o.incomplete) ||
                    (parentRaw != null && snapshot.objectById[parentRaw]?.incomplete == true)
            }
            .sortedWith(compareByDescending<Triple<HeapObject, String?, Long>> { it.third }
                .thenBy { it.first.rawObjectId })
            .map { (o, _, ret) ->
                val cls = snapshot.classById[o.classId]?.nameRaw ?: "<缺失类:${o.classId}>"
                val site = o.allocationSiteId?.let { snapshot.siteById[it]?.locationRaw }
                val dp = dom.parent(o.rawObjectId)?.takeUnless { it == superRoot }?.let(keyOf)
                LeakCandidate(
                    targetKey = keyOf(o.rawObjectId),
                    targetRawObjectId = o.rawObjectId,
                    className = cls,
                    retainedSizeBytes = ret,
                    sizeUnit = snapshot.coordinate.sizeUnit,
                    dominatorParentKey = dp,
                    allocationLocationRaw = site,
                    rootPaths = pathsByTarget[o.rawObjectId].orEmpty(),
                    incomplete = o.incomplete
                )
            }

        // 类群：按 classId 聚合（保留原始类名；改名对应在 Diff 层做）。
        val classGroups = snapshot.objects.groupBy { it.classId }.map { (cid, objs) ->
            ClassGroup(
                classId = cid,
                classNameRaw = snapshot.classById[cid]?.nameRaw ?: "<缺失类:$cid>",
                objectCount = objs.size,
                shallowSizeBytes = objs.sumOf { it.sizeBytes ?: 0L },
                retainedSizeBytes = objs.sumOf { retained[it.rawObjectId] ?: 0L },
                incompleteObjectCount = objs.count { it.incomplete },
                sizeUnit = snapshot.coordinate.sizeUnit
            )
        }.sortedWith(compareByDescending<ClassGroup> { it.retainedSizeBytes }.thenBy { it.classId })

        val summary = InputSummary(
            snapshotSha256 = snapshot.rawBytesSha256,
            rawSizeBytes = snapshot.rawSizeBytes,
            objectCount = snapshot.objects.size,
            edgeCount = snapshot.edges.size,
            classCount = snapshot.classes.size,
            incompleteChunkCount = snapshot.incompleteChunks.size,
            parserVersion = snapshot.parser.parserVersion,
            formatDialect = snapshot.parser.formatDialect
        )
        val basis = CoordinateBasis(
            sizeUnit = snapshot.coordinate.sizeUnit,
            addressBase = snapshot.coordinate.addressBase,
            timeBasis = snapshot.coordinate.timeBasis,
            snapshotTakenAtRaw = snapshot.coordinate.takenAtRaw,
            snapshotTakenAtEpochMs = snapshot.coordinate.takenAtEpochMs,
            validForInputSha = snapshot.rawBytesSha256,
            validForParamsVersion = params.version,
            parentDependencies = parentDependencies,
            invalidationReasons = invalidationReasons
        )

        return AnalysisResult(
            resultId = "result@${snapshot.snapshotId}@p${params.version}@${snapshot.rawBytesSha256.take(12)}",
            snapshotId = snapshot.snapshotId,
            snapshotLabel = snapshot.label,
            paramsVersion = params.version,
            weakPolicy = params.weakPolicy,
            inputSummary = summary,
            basis = basis,
            totalObjects = snapshot.objects.size,
            reachableObjects = reach.reachable.size,
            unreachableObjects = snapshot.objects.size - reach.reachable.size,
            excludedWeakEdgeIds = reach.excludedEdges,
            objects = objectViews,
            edges = edgeViews,
            rootPathsByTarget = pathsByTarget.toMap(),
            leakCandidates = leakCandidates,
            classGroups = classGroups,
            diagnostics = snapshot.diagnostics
        )
    }
}
