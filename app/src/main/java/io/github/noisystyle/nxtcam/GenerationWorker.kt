package io.github.noisystyle.nxtcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@ExperimentalCamera2Interop
class GenerationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    private val generationStore = GenerationStore(appContext)
    private val budgetStore = MonthlyBudgetStore(appContext)
    private val imageEditor = OpenAiImageEditor(appContext)

    override fun doWork(): Result {
        createNotificationChannel()
        setForegroundAsync(foregroundInfo("Preparing NXT development"))
        generationStore.recoverInterruptedJobs()

        val job = generationStore.list()
            .filter {
                it.status == GenerationStatus.READY_TO_SAVE ||
                    it.status == GenerationStatus.QUEUED
            }
            .minWithOrNull(
                compareBy<PendingGeneration> { it.status != GenerationStatus.READY_TO_SAVE }
                    .thenBy { it.createdAt }
            ) ?: return Result.success()

        return try {
            val result = if (job.status == GenerationStatus.READY_TO_SAVE) {
                saveReadyResult(job)
            } else {
                processQueuedJob(job)
            }
            val finishedJob = generationStore.find(job.id)
            if ((finishedJob == null || finishedJob.status == GenerationStatus.PAUSED) &&
                hasRunnableJobs()
            ) {
                GenerationWorkScheduler.enqueue(applicationContext)
            }
            result
        } finally {
            imageEditor.close()
        }
    }

    private fun processQueuedJob(job: PendingGeneration): Result {
        val source = File(job.sourcePath)
        if (!source.isFile) {
            budgetStore.release(job, status = "source_missing")
            generationStore.remove(job.id)
            deleteTemporaryFiles(job)
            GenerationEvents.notify(
                applicationContext,
                job.id,
                GenerationEvents.STATUS_FAILED,
                error = "Temporary photograph unavailable"
            )
            return Result.success()
        }

        val apiKey = SecureApiKeyStore(applicationContext).load()
        if (apiKey.isNullOrBlank()) {
            val message = "OpenAI key unavailable"
            generationStore.pauseRunnableJobs(message)
            GenerationEvents.notify(
                applicationContext,
                job.id,
                GenerationEvents.STATUS_FAILED,
                error = message
            )
            return Result.success()
        }

        generationStore.markProcessing(job.id)
        GenerationEvents.notify(
            applicationContext,
            job.id,
            GenerationEvents.STATUS_PROCESSING
        )
        setForegroundAsync(foregroundInfo("NXT development in progress"))

        return try {
            val edit = imageEditor.enhanceBlocking(
                source = source,
                apiKey = apiKey,
                look = job.look,
                quality = job.quality,
                capturedAtMillis = job.createdAt,
                captureZoneId = job.captureZoneId,
                useCaptureTimeContext = job.useCaptureTimeContext
            )
            val resultFile = File(source.parentFile, "nxt_result_${job.id}.jpg")
            resultFile.outputStream().use { it.write(edit.jpegBytes) }
            generationStore.markResultReady(job.id, resultFile.absolutePath, edit.actualCostUsd)
            budgetStore.reconcile(job, edit)
            saveReadyResult(
                generationStore.find(job.id)
                    ?: job.copy(
                        status = GenerationStatus.READY_TO_SAVE,
                        resultPath = resultFile.absolutePath,
                        actualCostUsd = edit.actualCostUsd
                    )
            )
        } catch (error: ImageEditException) {
            if (error.retryable) {
                generationStore.markQueued(job.id, error.message)
                GenerationEvents.notify(
                    applicationContext,
                    job.id,
                    GenerationEvents.STATUS_WAITING,
                    error = "Retrying automatically when possible"
                )
                Result.retry()
            } else {
                val message = error.message ?: "NXT development paused"
                generationStore.pauseRunnableJobs(message)
                GenerationEvents.notify(
                    applicationContext,
                    job.id,
                    GenerationEvents.STATUS_FAILED,
                    error = message
                )
                Result.success()
            }
        } catch (error: Exception) {
            generationStore.markQueued(job.id, error.message)
            Log.e(LOG_TAG, "NXT processing interrupted", error)
            Result.retry()
        }
    }

    private fun saveReadyResult(job: PendingGeneration): Result {
        val source = File(job.sourcePath)
        val resultFile = job.resultPath?.let(::File)
        if (!source.isFile || resultFile?.isFile != true) {
            generationStore.pause(job.id, "Temporary result unavailable")
            GenerationEvents.notify(
                applicationContext,
                job.id,
                GenerationEvents.STATUS_FAILED,
                error = "Temporary result unavailable; original preserved"
            )
            return Result.success()
        }

        return try {
            setForegroundAsync(foregroundInfo("Saving NXT photograph"))
            val uri = GallerySaver.saveEditedBytes(
                applicationContext,
                resultFile.readBytes(),
                "NXTCAM_${job.look.shortCode}_${job.quality.label}_${timestamp()}.jpg",
                source,
                preserveSourceCaptureTime = job.originalGalleryUri == null
            )
            job.originalGalleryUri?.let { original ->
                ImportLinkStore(applicationContext).apply {
                    link(uri, android.net.Uri.parse(original))
                    markDateNormalized(uri)
                }
            }
            generationStore.remove(job.id)
            deleteTemporaryFiles(job)
            GenerationEvents.notify(
                applicationContext,
                job.id,
                GenerationEvents.STATUS_COMPLETED,
                uri = uri
            )
            Result.success()
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Unable to save completed NXT image", error)
            GenerationEvents.notify(
                applicationContext,
                job.id,
                GenerationEvents.STATUS_WAITING,
                error = "Save pending: retrying automatically"
            )
            Result.retry()
        }
    }

    private fun hasRunnableJobs(): Boolean = generationStore.list().any {
        it.status == GenerationStatus.QUEUED || it.status == GenerationStatus.READY_TO_SAVE
    }

    private fun deleteTemporaryFiles(job: PendingGeneration) {
        File(job.sourcePath).delete()
        if (job.previewPath != job.sourcePath) File(job.previewPath).delete()
        job.resultPath?.let { File(it).delete() }
    }

    private fun foregroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_next_camera)
            .setContentTitle("NXT PROCESSING")
            .setContentText(message)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    Intent(applicationContext, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NXT development",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows NXT-CAM photographic development status"
            setSound(null, null)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private companion object {
        const val CHANNEL_ID = "nxt_processing"
        const val NOTIFICATION_ID = 2201
        const val LOG_TAG = "NxtProcessing"
    }
}
