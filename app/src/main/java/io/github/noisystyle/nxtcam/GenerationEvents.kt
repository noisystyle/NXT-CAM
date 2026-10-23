package io.github.noisystyle.nxtcam

import android.content.Context
import android.content.Intent
import android.net.Uri

object GenerationEvents {
    const val ACTION_STATE_CHANGED = "io.github.noisystyle.nxtcam.action.GENERATION_STATE_CHANGED"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_STATUS = "status"
    const val EXTRA_RESULT_URI = "result_uri"
    const val EXTRA_ERROR = "error"
    const val STATUS_PROCESSING = "processing"
    const val STATUS_WAITING = "waiting"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"

    fun notify(
        context: Context,
        jobId: String,
        status: String,
        uri: Uri? = null,
        error: String? = null
    ) {
        context.sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_RESULT_URI, uri?.toString())
                .putExtra(EXTRA_ERROR, error)
        )
    }
}
