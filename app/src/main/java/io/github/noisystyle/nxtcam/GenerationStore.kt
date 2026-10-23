package io.github.noisystyle.nxtcam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

enum class GenerationStatus {
    QUEUED,
    PROCESSING,
    READY_TO_SAVE,
    PAUSED
}

data class PendingGeneration(
    val id: String,
    val sourcePath: String,
    val previewPath: String,
    val look: PhotoLook,
    val quality: PhotoQuality,
    val createdAt: Long,
    val captureZoneId: String,
    val status: GenerationStatus,
    val reservation: BudgetReservation,
    val originalGalleryUri: String? = null,
    val useCaptureTimeContext: Boolean = true,
    val resultPath: String? = null,
    val actualCostUsd: Double? = null,
    val lastError: String? = null
)

class GenerationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun list(): List<PendingGeneration> = synchronized(LOCK) {
        decode(preferences.getString(KEY_JOBS, null)).sortedByDescending { it.createdAt }
    }

    fun find(id: String): PendingGeneration? = list().firstOrNull { it.id == id }

    fun add(job: PendingGeneration) = mutate { jobs ->
        jobs.removeAll { it.id == job.id }
        jobs += job
    }

    fun markProcessing(id: String) = update(id) {
        it.copy(status = GenerationStatus.PROCESSING, lastError = null)
    }

    fun markResultReady(id: String, resultPath: String, actualCostUsd: Double?) = update(id) {
        it.copy(
            status = GenerationStatus.READY_TO_SAVE,
            resultPath = resultPath,
            actualCostUsd = actualCostUsd,
            lastError = null
        )
    }

    fun markQueued(id: String, error: String? = null) = update(id) {
        it.copy(status = GenerationStatus.QUEUED, lastError = error)
    }

    fun pause(id: String, error: String) = update(id) {
        it.copy(status = GenerationStatus.PAUSED, lastError = error)
    }

    fun pauseRunnableJobs(error: String) = mutate { jobs ->
        jobs.replaceAll { job ->
            if (job.status == GenerationStatus.QUEUED ||
                job.status == GenerationStatus.PROCESSING
            ) {
                job.copy(status = GenerationStatus.PAUSED, lastError = error)
            } else {
                job
            }
        }
    }

    fun recoverInterruptedJobs() = mutate { jobs ->
        jobs.replaceAll { job ->
            if (job.status == GenerationStatus.PROCESSING) {
                job.copy(
                    status = GenerationStatus.QUEUED,
                    lastError = "Development interrupted: resuming automatically"
                )
            } else {
                job
            }
        }
    }

    fun requeuePausedJobs() = mutate { jobs ->
        jobs.replaceAll { job ->
            if (job.status == GenerationStatus.PAUSED) {
                job.copy(status = GenerationStatus.QUEUED, lastError = null)
            } else {
                job
            }
        }
    }

    fun remove(id: String): PendingGeneration? = synchronized(LOCK) {
        val jobs = decode(preferences.getString(KEY_JOBS, null)).toMutableList()
        val removed = jobs.firstOrNull { it.id == id }
        jobs.removeAll { it.id == id }
        save(jobs)
        removed
    }

    private fun update(id: String, transform: (PendingGeneration) -> PendingGeneration) =
        mutate { jobs -> jobs.replaceAll { if (it.id == id) transform(it) else it } }

    private fun mutate(block: (MutableList<PendingGeneration>) -> Unit) = synchronized(LOCK) {
        val jobs = decode(preferences.getString(KEY_JOBS, null)).toMutableList()
        block(jobs)
        save(jobs)
    }

    private fun save(jobs: List<PendingGeneration>) {
        val array = JSONArray()
        jobs.forEach { job ->
            array.put(
                JSONObject()
                    .put("id", job.id)
                    .put("sourcePath", job.sourcePath)
                    .put("previewPath", job.previewPath)
                    .put("look", job.look.name)
                    .put("quality", job.quality.name)
                    .put("createdAt", job.createdAt)
                    .put("captureZoneId", job.captureZoneId)
                    .put("status", job.status.name)
                    .put("reservationMonth", job.reservation.monthKey)
                    .put("reservationAmount", job.reservation.amountUsd)
                    .put("reservationQuality", job.reservation.qualityKey)
                    .put("originalGalleryUri", job.originalGalleryUri)
                    .put("useCaptureTimeContext", job.useCaptureTimeContext)
                    .put("resultPath", job.resultPath)
                    .put("actualCostUsd", job.actualCostUsd)
                    .put("lastError", job.lastError)
            )
        }
        preferences.edit().putString(KEY_JOBS, array.toString()).commit()
    }

    private fun decode(raw: String?): List<PendingGeneration> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PendingGeneration(
                            id = item.getString("id"),
                            sourcePath = item.getString("sourcePath"),
                            previewPath = item.getString("previewPath"),
                            look = parsePhotoLook(item.getString("look")),
                            quality = PhotoQuality.valueOf(item.getString("quality")),
                            createdAt = item.getLong("createdAt"),
                            captureZoneId = item.optString(
                                "captureZoneId",
                                ZoneId.systemDefault().id
                            ),
                            status = runCatching {
                                GenerationStatus.valueOf(item.getString("status"))
                            }.getOrDefault(GenerationStatus.QUEUED),
                            reservation = BudgetReservation(
                                monthKey = item.getString("reservationMonth"),
                                amountUsd = item.getDouble("reservationAmount"),
                                qualityKey = item.getString("reservationQuality")
                            ),
                            originalGalleryUri = item.optString("originalGalleryUri")
                                .takeIf { it.isNotBlank() && it != "null" },
                            useCaptureTimeContext = item.optBoolean("useCaptureTimeContext", true),
                            resultPath = item.optString("resultPath")
                                .takeIf { it.isNotBlank() && it != "null" },
                            actualCostUsd = item.optDouble("actualCostUsd", Double.NaN)
                                .takeIf { it.isFinite() && it >= 0.0 },
                            lastError = item.optString("lastError")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        fun parsePhotoLook(value: String): PhotoLook = when (value) {
            // Migrate queues created by versions that exposed these retired profiles.
            "STUDIO" -> PhotoLook.PORTRAIT
            "TATTOO", "FILM" -> PhotoLook.COLOR
            else -> runCatching { PhotoLook.valueOf(value) }.getOrDefault(PhotoLook.COLOR)
        }

        val LOCK = Any()
        const val PREFERENCES = "next_camera_generation_queue"
        const val KEY_JOBS = "jobs"
    }
}
