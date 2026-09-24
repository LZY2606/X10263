package heaplab.parse

import heaplab.model.*
import java.security.MessageDigest

data class ParseBudget(val maxBytes: Long = 10L * 1024 * 1024, val maxObjects: Int = 50_000)

/**
 * Line-oriented HSNAP parser.
 *
 * Grammar (one record per line):
 *   #!heap-snapshot v1            magic + format version
 *   SNAPSHOT id=.. captured_at=..
 *   CLASS id=.. name=.. super=.. loader=..
 *   SITE  id=.. location=.. line=..
 *   ROOT  id=.. category=.. object=..
 *   OBJECT id=.. address=.. class=.. size=.. unit=.. site=..
 *   FIELD object=.. key=.. value=..
 *   EDGE  id=.. kind=STRONG|WEAK|INTERNAL from=.. to=.. label=..
 *   END
 *
 * `PARTIAL ...` markers are expected to have no matching END.
 */
class SnapshotParser(private val budget: ParseBudget = ParseBudget()) {

    fun parse(source: String): ParseOutcome {
        val bytes = source.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val consumed = bytes.size.toLong()
        if (consumed > budget.maxBytes) {
            return ParseOutcome.BudgetExhausted(
                listOf(
                    Diagnostic(
                        DiagnosticKind.BUDGET_EXHAUSTED,
                        "BUDGET_BYTES",
                        "input ${consumed}B exceeds byte budget ${budget.maxBytes}B",
                        0, 1, 0L,
                    ),
                ),
                consumed,
                budget.maxBytes,
            )
        }
        return ParserState(source, budget, consumed, sha).run()
    }
}
