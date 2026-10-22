package it.noxia.leicaai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BudgetSnapshot(
    val monthKey: String,
    val limitUsd: Double?,
    val spentUsd: Double,
    val completedShots: Int,
    val measuredShots: Int,
    val averageMeasuredCostUsd: Double?
)

data class BudgetReservation(
    val monthKey: String,
    val amountUsd: Double,
    val qualityKey: String
)

data class MonthlyCostSummary(
    val monthKey: String,
    val spentUsd: Double,
    val completedShots: Int,
    val measuredShots: Int,
    val averageMeasuredCostUsd: Double?
)

private data class CostLogEntry(
    val eventAt: Long,
    val capturedAt: Long,
    val jobId: String,
    val monthKey: String,
    val status: String,
    val look: String,
    val quality: String,
    val model: String?,
    val estimatedUsd: Double,
    val actualUsd: Double?,
    val accountedUsd: Double,
    val costSource: String,
    val imageInputTokens: Long?,
    val textInputTokens: Long?,
    val imageOutputTokens: Long?
)

class MonthlyBudgetStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): BudgetSnapshot {
        val month = currentMonth()
        val measuredShots = intFor("$KEY_MEASURED_COUNT$month")
        val measuredTotal = doubleFor("$KEY_MEASURED_TOTAL$month")
        return BudgetSnapshot(
            monthKey = month,
            limitUsd = limitUsd(),
            spentUsd = spentFor(month),
            completedShots = intFor("$KEY_COMPLETED$month"),
            measuredShots = measuredShots,
            averageMeasuredCostUsd = if (measuredShots > 0) measuredTotal / measuredShots else null
        )
    }

    @Synchronized
    fun setLimitUsd(limit: Double?) {
        preferences.edit().apply {
            if (limit == null || limit <= 0.0) remove(KEY_LIMIT)
            else putString(KEY_LIMIT, limit.toString())
        }.apply()
    }

    @Synchronized
    fun canReserve(quality: PhotoQuality): Boolean {
        val limit = limitUsd() ?: return true
        val month = currentMonth()
        return spentFor(month) + estimatedCostFor(quality) <= limit + EPSILON
    }

    @Synchronized
    fun estimatedCostFor(quality: PhotoQuality): Double = estimateFor(quality)

    @Synchronized
    fun tryReserve(quality: PhotoQuality): BudgetReservation? {
        val month = currentMonth()
        val estimate = estimatedCostFor(quality)
        val limit = limitUsd()
        val spent = spentFor(month)
        if (limit != null && spent + estimate > limit + EPSILON) return null

        saveSpent(month, spent + estimate)
        return BudgetReservation(month, estimate, quality.name)
    }

    @Synchronized
    fun reconcile(job: PendingGeneration, edit: EditResult) {
        if (preferences.getBoolean("$KEY_FINALIZED${job.id}", false)) return
        val actualCost = edit.actualCostUsd?.takeIf { it.isFinite() && it >= 0.0 }
        val accountedCost = actualCost ?: job.reservation.amountUsd
        val spent = spentFor(job.reservation.monthKey)
        val measured = actualCost != null
        val log = costLogEntries().toMutableList().apply {
            add(
                CostLogEntry(
                    eventAt = System.currentTimeMillis(),
                    capturedAt = job.createdAt,
                    jobId = job.id,
                    monthKey = job.reservation.monthKey,
                    status = "completed",
                    look = job.look.name,
                    quality = job.quality.name,
                    model = edit.model,
                    estimatedUsd = job.reservation.amountUsd,
                    actualUsd = actualCost,
                    accountedUsd = accountedCost,
                    costSource = if (measured) "api_usage" else "estimate",
                    imageInputTokens = edit.imageInputTokens,
                    textInputTokens = edit.textInputTokens,
                    imageOutputTokens = edit.imageOutputTokens
                )
            )
        }.takeLast(MAX_LOG_ENTRIES)
        val editor = preferences.edit()
            .putString(
                "$KEY_SPENT${job.reservation.monthKey}",
                (spent - job.reservation.amountUsd + accountedCost).coerceAtLeast(0.0).toString()
            )
            .putInt(
                "$KEY_COMPLETED${job.reservation.monthKey}",
                intFor("$KEY_COMPLETED${job.reservation.monthKey}") + 1
            )
            .putBoolean("$KEY_FINALIZED${job.id}", true)
            .putString(KEY_COST_LOG, encodeCostLog(log))
        if (measured) {
            editor
                .putString("$KEY_LAST_COST${job.reservation.qualityKey}", actualCost.toString())
                .putInt(
                    "$KEY_MEASURED_COUNT${job.reservation.monthKey}",
                    intFor("$KEY_MEASURED_COUNT${job.reservation.monthKey}") + 1
                )
                .putString(
                    "$KEY_MEASURED_TOTAL${job.reservation.monthKey}",
                    (doubleFor("$KEY_MEASURED_TOTAL${job.reservation.monthKey}") + actualCost).toString()
                )
        }
        editor.commit()
    }

    @Synchronized
    fun release(job: PendingGeneration, status: String = "cancelled") {
        if (preferences.getBoolean("$KEY_FINALIZED${job.id}", false)) return
        val spent = spentFor(job.reservation.monthKey)
        val log = costLogEntries().toMutableList().apply {
            add(
                CostLogEntry(
                    eventAt = System.currentTimeMillis(),
                    capturedAt = job.createdAt,
                    jobId = job.id,
                    monthKey = job.reservation.monthKey,
                    status = status,
                    look = job.look.name,
                    quality = job.quality.name,
                    model = null,
                    estimatedUsd = job.reservation.amountUsd,
                    actualUsd = null,
                    accountedUsd = 0.0,
                    costSource = "released",
                    imageInputTokens = null,
                    textInputTokens = null,
                    imageOutputTokens = null
                )
            )
        }.takeLast(MAX_LOG_ENTRIES)
        preferences.edit()
            .putString(
                "$KEY_SPENT${job.reservation.monthKey}",
                (spent - job.reservation.amountUsd).coerceAtLeast(0.0).toString()
            )
            .putBoolean("$KEY_FINALIZED${job.id}", true)
            .putString(KEY_COST_LOG, encodeCostLog(log))
            .commit()
    }

    @Synchronized
    fun costLogCount(): Int = costLogEntries().size

    @Synchronized
    fun monthlySummaries(): Map<String, MonthlyCostSummary> {
        val months = preferences.all.keys
            .filter { it.startsWith(KEY_SPENT) }
            .map { it.removePrefix(KEY_SPENT) }
            .filter { runCatching { YearMonth.parse(it) }.isSuccess }
            .toMutableSet()
            .apply { add(currentMonth()) }
        return months.associateWith { month ->
            val measuredShots = intFor("$KEY_MEASURED_COUNT$month")
            val measuredTotal = doubleFor("$KEY_MEASURED_TOTAL$month")
            MonthlyCostSummary(
                monthKey = month,
                spentUsd = spentFor(month),
                completedShots = intFor("$KEY_COMPLETED$month"),
                measuredShots = measuredShots,
                averageMeasuredCostUsd = if (measuredShots > 0) {
                    measuredTotal / measuredShots
                } else {
                    null
                }
            )
        }
    }

    @Synchronized
    fun exportCostLogCsv(): String = buildString {
        append('\uFEFF')
        appendLine(
            "event_at_utc,captured_at_utc,month,job_id,status,look,quality,model," +
                "estimated_usd,actual_usd,accounted_usd,cost_source," +
                "image_input_tokens,text_input_tokens,image_output_tokens"
        )
        costLogEntries().forEach { entry ->
            appendCsvRow(
                isoTimestamp(entry.eventAt),
                isoTimestamp(entry.capturedAt),
                entry.monthKey,
                entry.jobId,
                entry.status,
                entry.look,
                entry.quality,
                entry.model.orEmpty(),
                formatCsvUsd(entry.estimatedUsd),
                entry.actualUsd?.let(::formatCsvUsd).orEmpty(),
                formatCsvUsd(entry.accountedUsd),
                entry.costSource,
                entry.imageInputTokens?.toString().orEmpty(),
                entry.textInputTokens?.toString().orEmpty(),
                entry.imageOutputTokens?.toString().orEmpty()
            )
        }
    }

    private fun estimateFor(quality: PhotoQuality): Double {
        val observed = preferences.getString("$KEY_LAST_COST${quality.name}", null)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
        if (observed != null) return observed * 1.15
        return when (quality) {
            PhotoQuality.HIGH -> 0.15
            PhotoQuality.MAX -> 0.30
        }
    }

    private fun limitUsd(): Double? = preferences.getString(KEY_LIMIT, null)
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }

    private fun spentFor(month: String): Double = preferences
        .getString("$KEY_SPENT$month", null)
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: 0.0

    private fun intFor(key: String): Int = preferences.getInt(key, 0).coerceAtLeast(0)

    private fun doubleFor(key: String): Double = preferences.getString(key, null)
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: 0.0

    private fun saveSpent(month: String, value: Double) {
        preferences.edit().putString("$KEY_SPENT$month", value.toString()).apply()
    }

    private fun costLogEntries(): List<CostLogEntry> {
        val raw = preferences.getString(KEY_COST_LOG, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        CostLogEntry(
                            eventAt = item.getLong("eventAt"),
                            capturedAt = item.getLong("capturedAt"),
                            jobId = item.getString("jobId"),
                            monthKey = item.getString("monthKey"),
                            status = item.getString("status"),
                            look = item.getString("look"),
                            quality = item.getString("quality"),
                            model = item.optString("model")
                                .takeIf { it.isNotBlank() && it != "null" },
                            estimatedUsd = item.getDouble("estimatedUsd"),
                            actualUsd = item.optDouble("actualUsd", Double.NaN)
                                .takeIf { it.isFinite() && it >= 0.0 },
                            accountedUsd = item.getDouble("accountedUsd"),
                            costSource = item.getString("costSource"),
                            imageInputTokens = item.optLong("imageInputTokens", -1)
                                .takeIf { it >= 0 },
                            textInputTokens = item.optLong("textInputTokens", -1)
                                .takeIf { it >= 0 },
                            imageOutputTokens = item.optLong("imageOutputTokens", -1)
                                .takeIf { it >= 0 }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeCostLog(entries: List<CostLogEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("eventAt", entry.eventAt)
                    .put("capturedAt", entry.capturedAt)
                    .put("jobId", entry.jobId)
                    .put("monthKey", entry.monthKey)
                    .put("status", entry.status)
                    .put("look", entry.look)
                    .put("quality", entry.quality)
                    .put("model", entry.model)
                    .put("estimatedUsd", entry.estimatedUsd)
                    .put("actualUsd", entry.actualUsd)
                    .put("accountedUsd", entry.accountedUsd)
                    .put("costSource", entry.costSource)
                    .put("imageInputTokens", entry.imageInputTokens)
                    .put("textInputTokens", entry.textInputTokens)
                    .put("imageOutputTokens", entry.imageOutputTokens)
            )
        }
    }.toString()

    private fun StringBuilder.appendCsvRow(vararg values: String) {
        appendLine(values.joinToString(",") { value ->
            "\"${value.replace("\"", "\"\"")}\""
        })
    }

    private fun isoTimestamp(epochMillis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    private fun formatCsvUsd(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun currentMonth(): String = YearMonth.now().toString().lowercase(Locale.US)

    private companion object {
        const val PREFERENCES = "monthly_api_budget"
        const val KEY_LIMIT = "limit_usd"
        const val KEY_SPENT = "spent_"
        const val KEY_LAST_COST = "last_cost_"
        const val KEY_COMPLETED = "completed_"
        const val KEY_MEASURED_COUNT = "measured_count_"
        const val KEY_MEASURED_TOTAL = "measured_total_"
        const val KEY_FINALIZED = "finalized_"
        const val KEY_COST_LOG = "cost_log_v1"
        const val MAX_LOG_ENTRIES = 5_000
        const val EPSILON = 0.000001
    }
}
