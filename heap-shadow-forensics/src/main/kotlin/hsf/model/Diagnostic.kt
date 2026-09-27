package hsf.model

import kotlinx.serialization.Serializable

enum class Severity { ERROR, WARNING, INFO }

@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
    /** 1-based line / byte-offset / element position inside the source material. */
    val line: Int? = null,
    val offset: Int? = null,
    val chunkId: String? = null,
    val objectId: String? = null,
    val field: String? = null,
    /** Original unconvertible representation, kept at the original position. */
    val rawValue: String? = null
)
