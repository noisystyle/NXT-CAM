package io.github.noisystyle.nxtcam

import android.content.Context

object OnboardingState {
    private const val PREFERENCES = "nxt_camera_preferences"
    private const val KEY_COMPLETED = "onboarding_2_5_photo_completed"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }
}
