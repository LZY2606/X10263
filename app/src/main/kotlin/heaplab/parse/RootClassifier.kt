package heaplab.parse

/**
 * root 分类规范化。保留原始 categoryRaw，这里只给出受控词汇，
 * 让“多 root 等长路径”能按分类稳定排序。
 */
object RootClassifier {
    const val UNKNOWN = "UNKNOWN"
    const val STICKY = "STICKY_CLASS"
    const val JNI_GLOBAL = "JNI_GLOBAL"
    const val JNI_LOCAL = "JNI_LOCAL"
    const val JAVA_FRAME = "JAVA_FRAME"
    const val NATIVE_STACK = "NATIVE_STACK"
    const val SYSTEM_CLASS = "SYSTEM_CLASS"
    const val THREAD = "THREAD"
    const val FINALIZING = "FINALIZING"
    const val DEBUGGER = "DEBUGGER"
    const val REFERENCE_CLEANUP = "REFERENCE_CLEANUP"

    private val alias = mapOf(
        "sticky" to STICKY, "sticky-class" to STICKY, "sticky_class" to STICKY,
        "jni-global" to JNI_GLOBAL, "jni_global" to JNI_GLOBAL, "jniglobal" to JNI_GLOBAL,
        "jni-local" to JNI_LOCAL, "jni_local" to JNI_LOCAL,
        "java-frame" to JAVA_FRAME, "java_frame" to JAVA_FRAME, "frame" to JAVA_FRAME,
        "native-stack" to NATIVE_STACK, "native_stack" to NATIVE_STACK,
        "system-class" to SYSTEM_CLASS, "system_class" to SYSTEM_CLASS, "system" to SYSTEM_CLASS,
        "thread" to THREAD,
        "finalizing" to FINALIZING, "finalizer" to FINALIZING,
        "debugger" to DEBUGGER,
        "reference-cleanup" to REFERENCE_CLEANUP, "reference_cleanup" to REFERENCE_CLEANUP
    )

    fun normalize(raw: String): String {
        if (raw.isBlank()) return UNKNOWN
        return alias[raw.trim().lowercase()] ?: raw.trim().uppercase().takeIf { it in known } ?: UNKNOWN
    }

    private val known = setOf(
        STICKY, JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK,
        SYSTEM_CLASS, THREAD, FINALIZING, DEBUGGER, REFERENCE_CLEANUP, UNKNOWN
    )
}
