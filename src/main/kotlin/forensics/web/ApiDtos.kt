package forensics.web

import forensics.ledger.TimelineView

data class ApiResponse(val ok: Boolean, val data: Any? = null, val errors: List<Map<String, String?>> = emptyList())

data class TimelinePayload(
    val view: TimelineView,
    val graphs: Map<String, String>,
    val currentSeq: Int,
    val decisions: Map<String, String>,
    val pendingWrites: List<Map<String, String?>>,
)
