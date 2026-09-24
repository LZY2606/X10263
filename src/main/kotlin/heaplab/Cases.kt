package heaplab

/**
 * 离线领域案例包：堆影取证室——内存快照、弱引用边与保留路径候选。
 * 初始版本完整保留原始对象 id、地址、类元数据、强/弱/内部边、root 分类、
 * 不完整块、allocation site 与解析器版本；随后安排：
 *  一次参数修订、一次只影响局部依赖的源数据改动、一次互相冲突的人工决定。
 */
object Cases {

    private val parserVersion = "hprof-lite/1.4.0"
    private val capturedAt = "2026-09-20T03:14:00Z"

    private fun classes(): List<ClassMeta> = listOf(
        ClassMeta("cls-registry", "com.acme.SessionRegistry", "cls-object", "app-loader"),
        ClassMeta("cls-session", "com.acme.Session", "cls-object", "app-loader"),
        ClassMeta("cls-cache", "com.acme.Cache", "cls-object", "app-loader"),
        ClassMeta("cls-buffer", "com.acme.Buffer", "cls-object", "app-loader"),
        ClassMeta("cls-node", "com.acme.Node", "cls-object", "app-loader"),
        ClassMeta("cls-shared", "com.acme.SharedPayload", "cls-object", "app-loader"),
        ClassMeta("cls-object", "java.lang.Object", null, "bootstrap"),
    )

    private fun sites() = listOf(
        AllocationSite("site-1", "com.acme.Bootstrap", "main", "Bootstrap.kt", 12),
        AllocationSite("site-2", "com.acme.SessionRegistry", "<init>", "SessionRegistry.kt", 31),
        AllocationSite("site-3", "com.acme.Cache", "holdBuffer", "Cache.kt", 58),
        AllocationSite("site-4", "com.acme.Node", "link", "Node.kt", 21),
    )

    /** 基线快照：含循环、弱引用、内部边、多 root、复用地址与不完整块。 */
    fun baseline(): Snapshot {
        val objects = listOf(
            HeapObject("obj-registry", "0x1010", 0x1010, "cls-registry", "48", 48, "site-2"),
            HeapObject("obj-session-1", "0x1020", 0x1020, "cls-session", "64", 64, "site-2"),
            HeapObject("obj-session-2", "0x1028", 0x1028, "cls-session", "64", 64, "site-2"),
            HeapObject("obj-cache", "0x1030", 0x1030, "cls-cache", "40", 40, "site-3"),
            HeapObject("obj-buffer", "0x1040", 0x1040, "cls-buffer", "4 KiB", 4096, "site-3"),
            HeapObject("obj-intern", "0x1050", 0x1050, "cls-buffer", "128", 128, "site-3"),
            HeapObject("obj-cycle-a", "0x1060", 0x1060, "cls-node", "24", 24, "site-4"),
            HeapObject("obj-cycle-b", "0x1068", 0x1068, "cls-node", "24", 24, "site-4"),
            HeapObject("obj-shared", "0x1070", 0x1070, "cls-shared", "512", 512, "site-2"),
            HeapObject("obj-dup-addr", "0x1020", 0x1020, "cls-buffer", "16", 16, "site-3"),
        )
        val edges = listOf(
            HeapEdge("e-reg-s1", "obj-registry", "obj-session-1", EdgeKind.STRONG, "sessions[0]"),
            HeapEdge("e-reg-s2", "obj-registry", "obj-session-2", EdgeKind.STRONG, "sessions[1]"),
            HeapEdge("e-reg-cache", "obj-registry", "obj-cache", EdgeKind.STRONG, "cache"),
            HeapEdge("e-cache-buf", "obj-cache", "obj-buffer", EdgeKind.WEAK, "weakBuffer"),
            HeapEdge("e-cache-intern", "obj-cache", "obj-intern", EdgeKind.INTERNAL, "static \$internals"),
            HeapEdge("e-reg-ca", "obj-registry", "obj-cycle-a", EdgeKind.STRONG, "nodes[0]"),
            HeapEdge("e-ca-cb", "obj-cycle-a", "obj-cycle-b", EdgeKind.STRONG, "next"),
            HeapEdge("e-cb-ca", "obj-cycle-b", "obj-cycle-a", EdgeKind.STRONG, "prev"),
            HeapEdge("e-s1-shared", "obj-session-1", "obj-shared", EdgeKind.STRONG, "payload"),
            HeapEdge("e-s2-shared", "obj-session-2", "obj-shared", EdgeKind.STRONG, "payload"),
        )
        val roots = listOf(
            RootRef("obj-registry", RootType.THREAD, "main-thread"),
            RootRef("obj-session-1", RootType.JNI_LOCAL, "jni frame #3"),
            RootRef("obj-session-2", RootType.JNI_GLOBAL, "global ref table"),
        )
        val blocks = listOf(
            IncompleteBlock("blk-7", "0x7F00", "256", "GC 在记录该堆块结束前被截断，长度为下界估计"),
        )
        return Snapshot(
            snapshotId = "snap-baseline",
            name = "堆影取证室·基线",
            parserVersion = parserVersion,
            capturedAt = capturedAt,
            classes = classes(),
            objects = objects,
            edges = edges,
            roots = roots,
            incompleteBlocks = blocks,
            allocationSites = sites(),
        )
    }

    /** 源数据改动（只影响局部依赖）：给环增加一条回边到 registry，支配结构局部变化。 */
    fun cycleAmended(): Snapshot =
        baseline().copy(
            snapshotId = "snap-cycle-backedge",
            name = "堆影取证室·源改动：环回边",
            edges = baseline().edges + HeapEdge("e-cb-reg", "obj-cycle-b", "obj-registry", EdgeKind.STRONG, "backRef"),
        )

    /** 不完整对象：类元数据引用断裂 + 无法转换的地址；导入应被拒绝并带位置诊断。 */
    fun broken(): Snapshot =
        baseline().copy(
            snapshotId = "snap-incomplete-attempt",
            name = "堆影取证室·不完整对象尝试",
            objects = baseline().objects + HeapObject(
                "obj-broken", "0x????", null, "cls-missing", "n/a", null, null, incomplete = true,
            ),
            incompleteBlocks = baseline().incompleteBlocks + IncompleteBlock("blk-8", "0x7F80", "?", "长度未知"),
        )

    /** 类改名：同一快照身份域内 classId 改变 + 名称改变；差异页表现为消失+新增。 */
    fun renamed(): Snapshot {
        val b = baseline()
        val newCacheId = "cls-cache-v2"
        return b.copy(
            snapshotId = "snap-renamed",
            name = "堆影取证室·类改名",
            capturedAt = "2026-09-20T04:14:00Z",
            classes = b.classes.filter { it.classId != "cls-cache" } +
                ClassMeta(newCacheId, "com.acme.Cache2", "cls-object", "app-loader"),
            objects = b.objects.map { if (it.classId == "cls-cache") it.copy(classId = newCacheId) else it },
        )
    }

    val weakOff = AnalysisParams(paramsVersion = 1, weakRefsKeepAlive = false, maxVisitedNodes = 5000)
    val weakOn = AnalysisParams(paramsVersion = 2, weakRefsKeepAlive = true, maxVisitedNodes = 5000)
}
