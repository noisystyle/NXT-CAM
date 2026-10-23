package io.github.noisystyle.nxtcam

import android.Manifest
import android.animation.AnimatorInflater
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.noisystyle.nxtcam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity(), SensorEventListener {
    private enum class LensMode { ULTRA_WIDE, WIDE, CROP_2X, CUSTOM }
    private enum class HapticCue {
        TAP,
        SELECT,
        TICK,
        TOGGLE_ON,
        TOGGLE_OFF,
        CONFIRM,
        REJECT,
        FOCUS,
        LOCK,
        GESTURE_START,
        GESTURE_END,
        SHUTTER
    }

    private companion object {
        const val CAMERA_LOG_TAG = "NxtCam"
        const val FOCUS_METERING_SIZE = 0.12f
        const val EXPOSURE_METERING_SIZE = 0.35f
        const val NIGHT_ENTER_LUX = 24f
        const val NIGHT_EXIT_LUX = 70f
        const val NIGHT_SWITCH_DELAY_MS = 1_300L
        const val NIGHT_SWITCH_COOLDOWN_MS = 4_000L
        const val PREVIEW_RECOVERY_DELAY_MS = 3_500L
        const val CAMERA_ERROR_RECOVERY_DELAY_MS = 350L
        const val MAX_PREVIEW_RECOVERY_ATTEMPTS = 2
        const val RESULT_PAN_MULTIPLIER = 1.7f
        const val CAMERA_PREFERENCES = "nxt_camera_preferences"
        const val LEGACY_PACKAGE_ID = "it.noxia.leicaai"
        const val SUPPORT_URL = "https://buymeacoffee.com/marcoprattv"
        const val KEY_GRID_ENABLED = "grid_enabled"
        const val KEY_PHOTO_QUALITY = "photo_quality"
        const val KEY_PHOTO_LOOK = "photo_look"
        const val KEY_LAST_ROLL_MONTH = "last_roll_month"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyStore: SecureApiKeyStore
    private lateinit var budgetStore: MonthlyBudgetStore
    private lateinit var generationStore: GenerationStore
    private lateinit var importLinkStore: ImportLinkStore
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var resultGestureDetector: GestureDetector
    private lateinit var orientationEventListener: OrientationEventListener

    private var rotationSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var previewUseCase: Preview? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraInitializationInProgress = false
    private var previewRecoveryAttempts = 0
    private var suppressExtensionsForRecovery = false
    private var cameraTargetRotation = Surface.ROTATION_0
    private var interfaceRotation = 0f
    private var minZoomRatio = 1f
    private var maxZoomRatio = 1f
    private var standardRearMinZoomRatio = 1f
    private var standardRearMaxZoomRatio = 1f
    private var rearUltraWideAvailable = false
    private var rearCrop2xAvailable = false
    private var lensMode = LensMode.WIDE
    private var usingFrontCamera = false
    private var forceStandardCamera = false
    private var activeExtensionMode = ExtensionMode.NONE
    private var nightExtensionRequested = false
    private var pendingNightExtension: Boolean? = null
    private var lastNightExtensionSwitchMs = 0L
    private var pendingRearLens = LensMode.WIDE
    private var photoLook = PhotoLook.COLOR
    private var photoQuality = PhotoQuality.HIGH
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var exposureMin = 0
    private var exposureMax = 0
    private var exposureIndex = 0
    private var exposureStep = 0f
    private var exposureAvailable = false
    private var exposureGestureStartIndex = 0
    private var lastHapticExposureIndex = Int.MIN_VALUE
    private var lastHapticZoomStep = Int.MIN_VALUE
    private var overlayEnabled = false
    private var focusLocked = false
    private var isAppResumed = false
    private val pendingGenerations = mutableListOf<PendingGeneration>()
    private val pendingJobs: Int
        get() = pendingGenerations.count { it.status != GenerationStatus.PAUSED }
    private val appPhotos = mutableListOf<GallerySaver.SavedPhoto>()
    private var currentPhotoIndex = -1
    private var resultScale = 1f
    private var resultTranslationX = 0f
    private var resultTranslationY = 0f
    private var generationReceiverRegistered = false
    private var isFoldLayout = false
    private var pendingCostLogCsv: String? = null
    private var viewerOpenedFromTimeline = false
    private var settingsOpen = false
    private var pendingImportFile: File? = null
    private var pendingImportOriginalUri: Uri? = null
    private var importLook = PhotoLook.COLOR
    private var importQuality = PhotoQuality.HIGH
    private var currentImportedOriginalUri: Uri? = null
    private var showingImportedOriginal = false
    private val timelineLoadingViews = mutableListOf<ThumbnailLoadingView>()
    private val timelineMonthAnchors = mutableListOf<TimelineMonthAnchor>()

    private val recoverPreview = Runnable {
        if (!isAppResumed || !isCameraViewVisible()) return@Runnable
        if (previewRecoveryAttempts >= MAX_PREVIEW_RECOVERY_ATTEMPTS) return@Runnable

        previewRecoveryAttempts += 1
        if (previewRecoveryAttempts == MAX_PREVIEW_RECOVERY_ATTEMPTS) {
            suppressExtensionsForRecovery = true
            Log.w(CAMERA_LOG_TAG, "Preview recovery falling back to standard CameraX pipeline")
        } else {
            Log.w(CAMERA_LOG_TAG, "Preview did not resume; rebuilding camera session")
        }
        bindCamera()
    }

    private sealed interface ViewerItem {
        val stableId: String

        data class Pending(val generation: PendingGeneration) : ViewerItem {
            override val stableId: String = "pending:${generation.id}"
        }

        data class Saved(val photo: GallerySaver.SavedPhoto) : ViewerItem {
            override val stableId: String = "saved:${photo.uri}"
        }
    }

    private data class TimelineMonthAnchor(
        val key: String,
        val header: View,
        val title: String,
        val subtitle: String
    )

    private val generationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GenerationEvents.ACTION_STATE_CHANGED) return
            val status = intent.getStringExtra(GenerationEvents.EXTRA_STATUS)
            val jobId = intent.getStringExtra(GenerationEvents.EXTRA_JOB_ID)
            val completedUri = intent.getStringExtra(GenerationEvents.EXTRA_RESULT_URI)
                ?.let(Uri::parse)
            val error = intent.getStringExtra(GenerationEvents.EXTRA_ERROR)
            val replaceVisiblePending =
                (currentViewerItem() as? ViewerItem.Pending)?.generation?.id == jobId
            refreshGenerationState(completedUri.takeIf { replaceVisiblePending })
            if (status == GenerationEvents.STATUS_COMPLETED) {
                playHaptic(HapticCue.CONFIRM)
            }
            if (status == GenerationEvents.STATUS_FAILED && !error.isNullOrBlank()) {
                playHaptic(HapticCue.REJECT)
                Toast.makeText(
                    this@MainActivity,
                    "NXT development paused: $error",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val applyPendingNightExtension = Runnable {
        val requested = pendingNightExtension ?: return@Runnable
        pendingNightExtension = null
        if (!isAppResumed || requested == nightExtensionRequested) return@Runnable
        nightExtensionRequested = requested
        lastNightExtensionSwitchMs = SystemClock.elapsedRealtime()
        Log.i(CAMERA_LOG_TAG, "Ambient light selected ${if (requested) "NIGHT" else "STANDARD"}")
        if (!forceStandardCamera) bindCamera()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
            requestNotificationPermissionIfNeeded()
        } else {
            showCameraPermissionError()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val legacyRollPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        loadPhotoLibrary()
    }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val importPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        cameraExecutor.execute {
            val workingCopy = runCatching { ImageUtils.copyUriToPending(this, uri) }
            runOnUiThread {
                workingCopy.onSuccess { file -> showImportEditor(file, uri) }
                    .onFailure { error ->
                        playHaptic(HapticCue.REJECT)
                        showError(
                            "Import failed",
                            error.message ?: "The photograph could not be read"
                        )
                    }
            }
        }
    }

    private val exportCostLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val csv = pendingCostLogCsv
        pendingCostLogCsv = null
        if (uri == null || csv == null) return@registerForActivityResult
        val saved = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(csv)
            } ?: error("Destination unavailable")
        }.isSuccess
        if (saved) {
            playHaptic(HapticCue.CONFIRM)
            Toast.makeText(this, "Cost log exported.", Toast.LENGTH_SHORT).show()
        } else {
            playHaptic(HapticCue.REJECT)
            Toast.makeText(this, "Export failed.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyStore = SecureApiKeyStore(this)
        budgetStore = MonthlyBudgetStore(this)
        generationStore = GenerationStore(this)
        importLinkStore = ImportLinkStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        pendingGenerations += generationStore.list()
        cleanupOrphanPendingFiles()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        configureOrientationListener()

        configureWindow()
        restoreCameraPreferences()
        configureGestures()
        configureResultGestures()
        configureControls()
        configureBackNavigation()
        applyAdaptiveFoldLayout()
        loadPhotoLibrary()
        requestCameraIfNeeded()
        if (pendingGenerations.isNotEmpty()) GenerationWorkScheduler.enqueue(this)

        if (keyStore.load().isNullOrBlank()) {
            binding.root.postDelayed({ showSettings(firstRun = true) }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        isAppResumed = true
        previewRecoveryAttempts = 0
        suppressExtensionsForRecovery = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (orientationEventListener.canDetectOrientation()) orientationEventListener.enable()
        updateRotationSensorRegistration()
        updateBudgetIndicator()
        refreshPendingGenerations()
        loadPhotoLibrary()
        updateProcessingIndicators()
        resumeCameraSession()
    }

    override fun onStart() {
        super.onStart()
        if (!generationReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                generationReceiver,
                IntentFilter(GenerationEvents.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            generationReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (generationReceiverRegistered) {
            unregisterReceiver(generationReceiver)
            generationReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onPause() {
        isAppResumed = false
        stopCameraSession()
        orientationEventListener.disable()
        binding.root.removeCallbacks(applyPendingNightExtension)
        pendingNightExtension = null
        sensorManager.unregisterListener(this)
        binding.thumbnailLoadingBorder.stop()
        binding.resultProcessingAnimation.stop()
        binding.generationEffect.stop()
        binding.foldProcessingAnimation.stop()
        binding.foldGenerationEffect.stop()
        timelineLoadingViews.forEach { it.stop() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.root.post {
            hideSystemBars()
            applyAdaptiveFoldLayout()
            applyInterfaceRotation(interfaceRotation, animated = false)
            ViewCompat.requestApplyInsets(binding.root)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (
                event.repeatCount == 0 &&
                binding.shutterButton.isEnabled &&
                isCameraViewVisible()
            ) {
                capturePhoto()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val navigationBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val gestureBottom = insets
                .getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
                .bottom
            val systemBottom = max(navigationBottom, gestureBottom)
            val safeBottom = max(
                systemBottom,
                dp(10f).roundToInt()
            )
            if (view.paddingBottom != safeBottom) {
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    safeBottom
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        hideSystemBars()
    }

    private fun configureOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (targetRotation == cameraTargetRotation) return

                cameraTargetRotation = targetRotation
                imageCapture?.targetRotation = targetRotation
                interfaceRotation = when (targetRotation) {
                    Surface.ROTATION_90 -> 90f
                    Surface.ROTATION_180 -> 180f
                    Surface.ROTATION_270 -> 270f
                    else -> 0f
                }
                refreshOrientationAwareControlStyles()
                applyInterfaceRotation(interfaceRotation, animated = true)
            }
        }
    }

    private fun applyInterfaceRotation(targetDegrees: Float, animated: Boolean) {
        val rotatingViews = listOf(
            binding.qualityButton,
            binding.gridButton,
            binding.flashButton,
            binding.settingsButton,
            binding.lensUltraWideButton,
            binding.lensWideButton,
            binding.lensCropButton,
            binding.frontCameraButton,
            binding.latestThumbnail,
            binding.importIcon,
            binding.shutterLevel,
            binding.deleteResultButton,
            binding.shareResultButton,
            binding.closeResultButton
        )
        rotatingViews.forEach { view ->
            view.animate().cancel()
            val nearestTarget = nearestRotation(view.rotation, targetDegrees)
            if (animated) {
                view.animate().rotation(nearestTarget).setDuration(180).start()
            } else {
                view.rotation = nearestTarget
            }
        }
    }

    private fun nearestRotation(current: Float, desired: Float): Float {
        var target = desired
        while (target - current > 180f) target -= 360f
        while (target - current < -180f) target += 360f
        return target
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun configureGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                exposureGestureStartIndex = exposureIndex
                lastHapticExposureIndex = exposureIndex
                binding.exposureGestureIndicator.animate().cancel()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val start = e1 ?: return false
                if (!exposureAvailable || scaleDetector.isInProgress) return false
                val verticalDistance = start.y - e2.y
                val horizontalDistance = abs(start.x - e2.x)
                if (abs(verticalDistance) < dp(14f) || abs(verticalDistance) < horizontalDistance * 1.2f) {
                    return false
                }
                val steps = (verticalDistance / dp(52f)).roundToInt()
                val target = (exposureGestureStartIndex + steps).coerceIn(exposureMin, exposureMax)
                if (target != exposureIndex) {
                    applyExposureIndex(target)
                    if (target != lastHapticExposureIndex) {
                        playHaptic(HapticCue.TICK)
                        lastHapticExposureIndex = target
                    }
                }
                showExposureIndicator()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                focusAt(e.x, e.y, locked = false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                focusAt(e.x, e.y, locked = true)
            }
        })
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return false
                    lastHapticZoomStep = (zoom * 4f).roundToInt()
                    playHaptic(HapticCue.GESTURE_START)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val target = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(minZoomRatio, maxZoomRatio)
                    lensMode = LensMode.CUSTOM
                    camera?.cameraControl?.setZoomRatio(target)
                    updateLensSelection(target)
                    val hapticStep = (target * 4f).roundToInt()
                    if (hapticStep != lastHapticZoomStep) {
                        playHaptic(HapticCue.TICK)
                        lastHapticZoomStep = hapticStep
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    playHaptic(HapticCue.GESTURE_END)
                }
            }
        )
    }

    private fun configureResultGestures() {
        resultGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    playHaptic(HapticCue.SELECT)
                    if (resultScale > 1.05f) {
                        setResultZoom(1f, animated = true)
                    } else {
                        val targetScale = 2.5f
                        val centerX = binding.resultImage.width / 2f
                        val centerY = binding.resultImage.height / 2f
                        resultScale = targetScale
                        resultTranslationX = (centerX - e.x) * (targetScale - 1f)
                        resultTranslationY = (centerY - e.y) * (targetScale - 1f)
                        clampResultTranslation()
                        applyResultTransform(animated = true)
                    }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (resultScale <= 1.05f) return false
                    resultTranslationX -= distanceX * RESULT_PAN_MULTIPLIER
                    resultTranslationY -= distanceY * RESULT_PAN_MULTIPLIER
                    clampResultTranslation()
                    applyResultTransform(animated = false)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    if (resultScale > 1.05f) return false
                    val distanceX = e2.x - start.x
                    val distanceY = e2.y - start.y
                    if (abs(distanceX) < dp(64f) || abs(distanceX) < abs(distanceY) * 1.25f) {
                        return false
                    }
                    val targetIndex = if (distanceX < 0f) {
                        currentPhotoIndex + 1
                    } else {
                        currentPhotoIndex - 1
                    }
                    if (targetIndex !in viewerItems().indices) return false
                    playHaptic(HapticCue.TICK)
                    showPhotoAt(targetIndex)
                    return true
                }
            }
        )
    }

    private fun configureControls() {
        binding.previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.performClick()
                hideExposureIndicator()
            }
            true
        }
        binding.previewView.setOnClickListener { }
        binding.resultImage.setOnTouchListener { view, event ->
            resultGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.performClick()
            }
            true
        }
        binding.resultImage.setOnClickListener { }
        binding.shutterButton.setOnClickListener { capturePhoto() }
        binding.lensUltraWideButton.setOnClickListener { selectLens(LensMode.ULTRA_WIDE) }
        binding.lensWideButton.setOnClickListener { selectLens(LensMode.WIDE) }
        binding.lensCropButton.setOnClickListener { selectLens(LensMode.CROP_2X) }
        binding.frontCameraButton.setOnClickListener { toggleCameraFacing() }
        binding.qualityButton.setOnClickListener { toggleQuality() }
        binding.gridButton.setOnClickListener { toggleCompositionOverlay() }
        binding.flashButton.setOnClickListener { cycleFlash() }
        binding.settingsButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            showSettings(firstRun = false)
        }
        binding.settingsCloseButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            hideSettings(animated = true)
        }
        binding.settingsSaveButton.setOnClickListener { saveSettings() }
        binding.settingsExportButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            pendingCostLogCsv = budgetStore.exportCostLogCsv()
            val fileDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exportCostLogLauncher.launch("NXT-CAM_costs_$fileDate.csv")
        }
        binding.settingsClearKeyButton.setOnClickListener {
            keyStore.clear()
            binding.settingsApiKeyInput.text?.clear()
            updateSettingsContent()
            playHaptic(HapticCue.CONFIRM)
            Toast.makeText(this, "Key removed from this device.", Toast.LENGTH_SHORT).show()
        }
        binding.settingsOnboardingButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            onboardingLauncher.launch(
                Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_REPLAY, true)
            )
        }
        binding.settingsSupportButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
            }.onFailure {
                playHaptic(HapticCue.REJECT)
                Toast.makeText(this, "Unable to open the support page.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.monthlyBudgetBar.setOnClickListener {
            playHaptic(HapticCue.TAP)
            showRollStatus()
        }

        binding.colorLookButton.setOnClickListener {
            setPhotoLook(PhotoLook.COLOR, hapticCue = HapticCue.SELECT)
        }
        binding.blackAndWhiteLookButton.setOnClickListener {
            setPhotoLook(PhotoLook.BLACK_AND_WHITE, hapticCue = HapticCue.SELECT)
        }
        binding.portraitLookButton.setOnClickListener {
            setPhotoLook(PhotoLook.PORTRAIT, hapticCue = HapticCue.SELECT)
        }
        binding.importButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            openPhotoLibrary()
        }
        binding.foldPreviewPanel.setOnClickListener {
            playHaptic(HapticCue.TAP)
            openPhotoLibrary()
        }
        binding.closeResultButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            closePhotoViewer()
        }
        binding.newShotButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            returnToCamera()
        }
        binding.shareResultButton.setOnClickListener { shareResult() }
        binding.deleteResultButton.setOnClickListener { confirmDeleteCurrentPhoto() }
        binding.timelineCloseButton.setOnClickListener {
            playHaptic(HapticCue.TAP)
            closeRollTimeline()
        }
        binding.timelineImportButton.setOnClickListener { choosePhotoToImport() }
        binding.importEditorCloseButton.setOnClickListener { cancelImportEditor() }
        binding.importCancelButton.setOnClickListener { cancelImportEditor() }
        binding.importDevelopButton.setOnClickListener { enqueueImportedPhoto() }
        binding.importColorLookButton.setOnClickListener { setImportLook(PhotoLook.COLOR) }
        binding.importBlackAndWhiteLookButton.setOnClickListener {
            setImportLook(PhotoLook.BLACK_AND_WHITE)
        }
        binding.importPortraitLookButton.setOnClickListener { setImportLook(PhotoLook.PORTRAIT) }
        binding.importHighQualityButton.setOnClickListener { setImportQuality(PhotoQuality.HIGH) }
        binding.importMaxQualityButton.setOnClickListener { setImportQuality(PhotoQuality.MAX) }
        binding.compareResultButton.setOnClickListener { toggleImportedComparison() }
        binding.timelineScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateStickyMonthHeader(scrollY)
        }

        setPhotoLook(photoLook)
        updateQualityButton()
        updateGridButton()
        updateFlashButton()
        updateSettingsButton()
        updateBudgetIndicator()
    }

    private fun applyAdaptiveFoldLayout() {
        val shouldUseFoldLayout = resources.configuration.screenWidthDp >= 600
        isFoldLayout = shouldUseFoldLayout
        val constraints = ConstraintSet().apply { clone(binding.root) }
        val cameraStart = if (shouldUseFoldLayout) R.id.foldDivider else ConstraintSet.PARENT_ID
        listOf(
            R.id.viewfinderContainer,
            R.id.cameraPreviewOverlay,
            R.id.compositionOverlay,
            R.id.captureFlash
        ).forEach { id ->
            constraints.connect(id, ConstraintSet.START, cameraStart, ConstraintSet.END.takeIf {
                shouldUseFoldLayout
            } ?: ConstraintSet.START)
            constraints.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }
        listOf(
            R.id.toolRow,
            R.id.captureRow,
            R.id.toolCaptureDivider,
            R.id.captureModeDivider,
            R.id.focusLockBadge
        ).forEach { id ->
            constraints.connect(id, ConstraintSet.START, cameraStart, ConstraintSet.END.takeIf {
                shouldUseFoldLayout
            } ?: ConstraintSet.START)
            constraints.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }

        constraints.clear(R.id.lensSelector, ConstraintSet.START)
        constraints.clear(R.id.lensSelector, ConstraintSet.END)
        listOf(R.id.lensSelector).forEach { id ->
            constraints.clear(id, ConstraintSet.START)
            constraints.connect(
                id,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                0
            )
        }
        constraints.clear(R.id.lookSelector, ConstraintSet.START)
        constraints.clear(R.id.lookSelector, ConstraintSet.END)
        constraints.connect(
            R.id.lookSelector,
            ConstraintSet.START,
            cameraStart,
            if (shouldUseFoldLayout) ConstraintSet.END else ConstraintSet.START,
            0
        )
        constraints.connect(
            R.id.lookSelector,
            ConstraintSet.END,
            R.id.lensSelector,
            ConstraintSet.START,
            0
        )
        constraints.setVisibility(
            R.id.foldPreviewPanel,
            if (shouldUseFoldLayout) View.VISIBLE else View.GONE
        )
        constraints.applyTo(binding.root)
        updateFoldPreviewPanel()

        if (isAppResumed && isCameraViewVisible()) {
            binding.previewView.post { bindCamera() }
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.importEditorOverlay.visibility == View.VISIBLE -> cancelImportEditor()
                        binding.resultOverlay.visibility == View.VISIBLE -> closePhotoViewer()
                        binding.rollTimelineOverlay.visibility == View.VISIBLE -> closeRollTimeline()
                        settingsOpen -> hideSettings(animated = true)
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )
    }

    private fun setPhotoLook(
        look: PhotoLook,
        hapticCue: HapticCue? = null
    ) {
        hapticCue?.let(::playHaptic)
        photoLook = look
        getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHOTO_LOOK, look.name)
            .apply()
        setLookSelected(binding.colorLookButton, look == PhotoLook.COLOR)
        setLookSelected(binding.blackAndWhiteLookButton, look == PhotoLook.BLACK_AND_WHITE)
        setLookSelected(binding.portraitLookButton, look == PhotoLook.PORTRAIT)
        applyPreviewLook(look)
    }

    private fun setLookSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.selected_light else android.R.color.transparent
        )
        view.setTextColor(if (selected) Color.BLACK else 0xA6FFFFFF.toInt())
        view.typeface = Typeface.create(
            if (selected) "sans-serif-black" else "sans-serif-medium",
            Typeface.NORMAL
        )
        view.alpha = if (selected) 1f else 0.82f
    }

    private fun setLensSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) selectedRotatingControlBackground() else android.R.color.transparent
        )
        view.setTextColor(if (selected) Color.BLACK else 0xBFFFFFFF.toInt())
    }

    private fun selectedRotatingControlBackground(): Int =
        R.drawable.selected_light

    private fun refreshOrientationAwareControlStyles() {
        setLensSelected(binding.lensUltraWideButton, !usingFrontCamera && lensMode == LensMode.ULTRA_WIDE)
        setLensSelected(binding.lensWideButton, !usingFrontCamera && lensMode == LensMode.WIDE)
        setLensSelected(binding.lensCropButton, !usingFrontCamera && lensMode == LensMode.CROP_2X)
        binding.frontCameraButton.setBackgroundResource(
            android.R.color.transparent
        )
        binding.frontCameraButton.imageTintList = ColorStateList.valueOf(
            Color.WHITE
        )
        binding.frontCameraButton.alpha = if (usingFrontCamera) 1f else 0.72f
        setLookSelected(binding.colorLookButton, photoLook == PhotoLook.COLOR)
        setLookSelected(binding.blackAndWhiteLookButton, photoLook == PhotoLook.BLACK_AND_WHITE)
        setLookSelected(binding.portraitLookButton, photoLook == PhotoLook.PORTRAIT)
        updateQualityButton()
        updateGridButton()
        updateFlashButton()
        updateSettingsButton()
    }

    private fun applyPreviewLook(look: PhotoLook) {
        val saturation = when (look) {
            PhotoLook.BLACK_AND_WHITE -> 0f
            PhotoLook.COLOR -> 1.08f
            PhotoLook.PORTRAIT -> 1.02f
        }
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        val contrast = when (look) {
            PhotoLook.BLACK_AND_WHITE -> 1.12f
            PhotoLook.COLOR -> 1.08f
            PhotoLook.PORTRAIT -> 1.045f
        }
        val offset = 128f * (1f - contrast)
        val redScale = when (look) {
            PhotoLook.PORTRAIT -> 1f
            else -> 1.015f
        }
        val blueScale = when (look) {
            PhotoLook.PORTRAIT -> 1f
            else -> 0.985f
        }
        val warmOffset = when (look) {
            PhotoLook.PORTRAIT -> 0f
            else -> 1.5f
        }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast * redScale, 0f, 0f, 0f, offset + warmOffset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast * blueScale, 0f, offset - warmOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        binding.previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        binding.previewView.setLayerPaint(paint)
        binding.cameraPreviewOverlay.strength = when (look) {
            PhotoLook.BLACK_AND_WHITE -> 0.78f
            PhotoLook.COLOR -> 0.62f
            PhotoLook.PORTRAIT -> 0.34f
        }
        binding.previewView.invalidate()
    }

    private fun toggleQuality() {
        photoQuality = if (photoQuality == PhotoQuality.HIGH) PhotoQuality.MAX else PhotoQuality.HIGH
        playHaptic(
            if (photoQuality == PhotoQuality.MAX) HapticCue.TOGGLE_ON else HapticCue.TOGGLE_OFF
        )
        getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHOTO_QUALITY, photoQuality.name)
            .apply()
        updateQualityButton()
        updateBudgetIndicator()
        Toast.makeText(
            this,
            if (photoQuality == PhotoQuality.MAX) "MAX quality: more detail, time and cost." else "HIGH quality: faster and less expensive.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateQualityButton() {
        binding.qualityButton.text = photoQuality.label
        binding.qualityButton.setTextColor(
            if (photoQuality == PhotoQuality.MAX) Color.BLACK else Color.WHITE
        )
        binding.qualityButton.setBackgroundResource(
            if (photoQuality == PhotoQuality.MAX) {
                selectedRotatingControlBackground()
            } else {
                android.R.color.transparent
            }
        )
    }

    private fun toggleCompositionOverlay() {
        overlayEnabled = !overlayEnabled
        playHaptic(if (overlayEnabled) HapticCue.TOGGLE_ON else HapticCue.TOGGLE_OFF)
        getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GRID_ENABLED, overlayEnabled)
            .apply()
        binding.compositionOverlay.enabledOverlay = overlayEnabled
        binding.shutterLevel.enabledLevel = true
        updateGridButton()
    }

    private fun updateGridButton() {
        binding.gridButton.setBackgroundResource(
            if (overlayEnabled) selectedRotatingControlBackground() else android.R.color.transparent
        )
        binding.gridButton.imageTintList = ColorStateList.valueOf(
            if (overlayEnabled) Color.BLACK else Color.WHITE
        )
    }

    private fun restoreCameraPreferences() {
        val preferences = getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
        overlayEnabled = preferences.getBoolean(KEY_GRID_ENABLED, false)
        photoQuality = runCatching {
            PhotoQuality.valueOf(
                preferences.getString(KEY_PHOTO_QUALITY, PhotoQuality.HIGH.name)
                    ?: PhotoQuality.HIGH.name
            )
        }.getOrDefault(PhotoQuality.HIGH)
        photoLook = when (val savedLook = preferences.getString(KEY_PHOTO_LOOK, null)) {
            "STUDIO" -> PhotoLook.PORTRAIT
            "TATTOO", "FILM" -> PhotoLook.COLOR
            else -> runCatching {
                PhotoLook.valueOf(savedLook ?: PhotoLook.COLOR.name)
            }.getOrDefault(PhotoLook.COLOR)
        }
        binding.compositionOverlay.enabledOverlay = overlayEnabled
        binding.shutterLevel.enabledLevel = true
    }

    private fun updateRotationSensorRegistration() {
        sensorManager.unregisterListener(this)
        if (!isAppResumed || !isCameraViewVisible()) return
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun cycleFlash() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        if (!hasFlash) {
            playHaptic(HapticCue.REJECT)
            Toast.makeText(this, "Flash is unavailable on this camera.", Toast.LENGTH_SHORT).show()
            return
        }
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        playHaptic(
            if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                HapticCue.TOGGLE_OFF
            } else {
                HapticCue.TOGGLE_ON
            }
        )
        imageCapture?.flashMode = flashMode
        updateFlashButton()
    }

    private fun updateFlashButton() {
        binding.flashButton.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ph_lightning_a
                ImageCapture.FLASH_MODE_ON -> R.drawable.ph_lightning
                else -> R.drawable.ph_lightning_slash
            }
        )
        binding.flashButton.setBackgroundResource(
            if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                android.R.color.transparent
            } else {
                selectedRotatingControlBackground()
            }
        )
        binding.flashButton.imageTintList = ColorStateList.valueOf(
            if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.WHITE else Color.BLACK
        )
        binding.flashButton.imageAlpha = if (flashMode == ImageCapture.FLASH_MODE_OFF) 135 else 255
        binding.flashButton.contentDescription = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "Auto flash"
            ImageCapture.FLASH_MODE_ON -> "Flash on"
            else -> "Flash off"
        }
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            requestNotificationPermissionIfNeeded()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startCamera() {
        if (cameraProvider != null) {
            resumeCameraSession()
            return
        }
        if (cameraInitializationInProgress) return
        cameraInitializationInProgress = true
        val mainExecutor = ContextCompat.getMainExecutor(this)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }
                .onFailure {
                    cameraInitializationInProgress = false
                    Log.e(CAMERA_LOG_TAG, "Camera provider initialization failed", it)
                    if (isAppResumed) {
                        showError("Camera unavailable", it.message ?: "CameraX error")
                    }
                }
                .getOrNull() ?: return@addListener
            cameraProvider = provider
            updateRearCameraCapabilities(provider)
            val extensionsFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extensionsFuture.addListener({
                extensionsManager = runCatching { extensionsFuture.get() }
                    .onFailure { Log.w(CAMERA_LOG_TAG, "OEM extensions unavailable", it) }
                    .getOrNull()
                cameraInitializationInProgress = false
                logExtensionSupport()
                updateRotationSensorRegistration()
                resumeCameraSession()
            }, mainExecutor)
        }, mainExecutor)
    }

    private fun resumeCameraSession() {
        if (!isAppResumed || !isCameraViewVisible()) return
        if (cameraInitializationInProgress) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        binding.previewView.post {
            if (!isAppResumed || !isCameraViewVisible()) return@post
            if (cameraProvider == null) startCamera() else bindCamera()
        }
    }

    private fun stopCameraSession() {
        binding.root.removeCallbacks(recoverPreview)
        binding.previewView.animate().cancel()
        binding.previewView.alpha = 0f
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        binding.previewView.previewStreamState.removeObservers(this)
        previewUseCase?.setSurfaceProvider(null)
        cameraProvider?.unbindAll()
        previewUseCase = null
        imageCapture = null
        camera = null
    }

    private fun isCameraViewVisible(): Boolean =
        binding.resultOverlay.visibility != View.VISIBLE &&
            binding.rollTimelineOverlay.visibility != View.VISIBLE &&
            binding.importEditorOverlay.visibility != View.VISIBLE &&
            binding.settingsOverlay.visibility != View.VISIBLE

    private fun schedulePreviewRecovery(delayMs: Long, reason: String) {
        if (!isAppResumed || !isCameraViewVisible()) return
        Log.w(CAMERA_LOG_TAG, "Scheduling preview recovery: $reason")
        binding.root.removeCallbacks(recoverPreview)
        binding.root.postDelayed(recoverPreview, delayMs)
    }

    private fun bindCamera() {
        if (!isAppResumed || !isCameraViewVisible()) return
        val provider = cameraProvider ?: return
        binding.root.removeCallbacks(recoverPreview)
        binding.previewView.animate().cancel()
        binding.previewView.alpha = 0f
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        binding.previewView.previewStreamState.removeObservers(this)
        previewUseCase?.setSurfaceProvider(null)
        val fourThreeResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(fourThreeResolution)
            .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
            .build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }
        previewUseCase = preview
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(fourThreeResolution)
            .setTargetRotation(cameraTargetRotation)
            .setFlashMode(flashMode)
            .build()

        val baseSelector = if (usingFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val (preferredSelector, preferredMode) = resolveOemCameraSelector(baseSelector)

        try {
            provider.unbindAll()
            val boundCamera = try {
                provider.bindToLifecycle(
                    this,
                    preferredSelector,
                    preview,
                    imageCapture
                ).also {
                    activeExtensionMode = preferredMode
                }
            } catch (extensionError: Exception) {
                if (preferredMode == ExtensionMode.NONE) throw extensionError
                Log.w(
                    CAMERA_LOG_TAG,
                    "OEM ${extensionModeName(preferredMode)} bind failed; using standard pipeline",
                    extensionError
                )
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    baseSelector,
                    preview,
                    imageCapture
                ).also {
                    activeExtensionMode = ExtensionMode.NONE
                }
            }
            camera = boundCamera.also { boundCamera ->
                Log.i(
                    CAMERA_LOG_TAG,
                    "Bound ${if (usingFrontCamera) "front" else "rear"} camera with " +
                        extensionModeName(activeExtensionMode)
                )
                configureExposure(boundCamera)
                boundCamera.cameraInfo.cameraState.observe(this) { state ->
                    state.error?.let { error ->
                        schedulePreviewRecovery(
                            CAMERA_ERROR_RECOVERY_DELAY_MS,
                            "CameraX error ${error.code}"
                        )
                    }
                }
                boundCamera.cameraInfo.zoomState.observe(this) { state ->
                    minZoomRatio = state.minZoomRatio
                    maxZoomRatio = state.maxZoomRatio
                    if (usingFrontCamera) {
                        updateFrontCameraSelection()
                    } else {
                        updateLensSelection(state.zoomRatio)
                    }
                }
                if (usingFrontCamera) {
                    updateFrontCameraSelection()
                } else {
                    boundCamera.cameraInfo.zoomState.value?.let { state ->
                        minZoomRatio = state.minZoomRatio
                        maxZoomRatio = state.maxZoomRatio
                    }
                    applyRearLens(boundCamera, pendingRearLens)
                }
            }
            binding.previewView.previewStreamState.observe(this) { state ->
                when (state) {
                    androidx.camera.view.PreviewView.StreamState.STREAMING -> {
                        binding.root.removeCallbacks(recoverPreview)
                        previewRecoveryAttempts = 0
                        binding.previewView.animate()
                            .alpha(1f)
                            .setDuration(140L)
                            .start()
                    }
                    androidx.camera.view.PreviewView.StreamState.IDLE -> {
                        schedulePreviewRecovery(PREVIEW_RECOVERY_DELAY_MS, "stream remained idle")
                    }
                }
            }
            schedulePreviewRecovery(PREVIEW_RECOVERY_DELAY_MS, "waiting for first frame")
        } catch (error: Exception) {
            schedulePreviewRecovery(CAMERA_ERROR_RECOVERY_DELAY_MS, error.message ?: "bind failure")
            if (previewRecoveryAttempts >= MAX_PREVIEW_RECOVERY_ATTEMPTS) {
                showError("Camera unavailable", error.message ?: "CameraX error")
            }
        }
    }

    private fun resolveOemCameraSelector(
        baseSelector: CameraSelector
    ): Pair<CameraSelector, Int> {
        if (forceStandardCamera || suppressExtensionsForRecovery) {
            return baseSelector to ExtensionMode.NONE
        }
        val manager = extensionsManager ?: return baseSelector to ExtensionMode.NONE
        val preferredModes = buildList {
            if (nightExtensionRequested) add(ExtensionMode.NIGHT)
            add(ExtensionMode.AUTO)
            add(ExtensionMode.HDR)
        }
        preferredModes.forEach { mode ->
            if (runCatching { manager.isExtensionAvailable(baseSelector, mode) }.getOrDefault(false)) {
                return manager.getExtensionEnabledCameraSelector(baseSelector, mode) to mode
            }
        }
        return baseSelector to ExtensionMode.NONE
    }

    private fun updateRearCameraCapabilities(provider: ProcessCameraProvider) {
        val rearCameraInfo = runCatching {
            CameraSelector.DEFAULT_BACK_CAMERA
                .filter(provider.availableCameraInfos)
                .firstOrNull()
        }.getOrNull()
        val zoomState = rearCameraInfo?.zoomState?.value

        standardRearMinZoomRatio = zoomState?.minZoomRatio ?: 1f
        standardRearMaxZoomRatio = zoomState?.maxZoomRatio ?: 1f
        rearUltraWideAvailable = standardRearMinZoomRatio < 0.95f
        rearCrop2xAvailable = standardRearMaxZoomRatio >= 2f

        binding.lensUltraWideButton.visibility =
            if (rearUltraWideAvailable) View.VISIBLE else View.GONE
        binding.lensCropButton.visibility =
            if (rearCrop2xAvailable) View.VISIBLE else View.GONE

        if (!rearUltraWideAvailable && pendingRearLens == LensMode.ULTRA_WIDE) {
            pendingRearLens = LensMode.WIDE
        }
        if (!rearCrop2xAvailable && pendingRearLens == LensMode.CROP_2X) {
            pendingRearLens = LensMode.WIDE
        }

        Log.i(
            CAMERA_LOG_TAG,
            "Rear zoom capabilities: min=$standardRearMinZoomRatio, " +
                "max=$standardRearMaxZoomRatio, ultraWide=$rearUltraWideAvailable, " +
                "crop2x=$rearCrop2xAvailable"
        )
    }

    private fun logExtensionSupport() {
        val manager = extensionsManager ?: return
        listOf(
            "rear" to CameraSelector.DEFAULT_BACK_CAMERA,
            "front" to CameraSelector.DEFAULT_FRONT_CAMERA
        ).forEach { (label, selector) ->
            val supported = listOf(
                ExtensionMode.AUTO,
                ExtensionMode.HDR,
                ExtensionMode.NIGHT,
                ExtensionMode.BOKEH,
                ExtensionMode.FACE_RETOUCH
            ).filter { mode ->
                runCatching { manager.isExtensionAvailable(selector, mode) }.getOrDefault(false)
            }.joinToString { extensionModeName(it) }
            Log.i(CAMERA_LOG_TAG, "OEM extensions $label: ${supported.ifBlank { "none" }}")
        }
    }

    private fun extensionModeName(mode: Int): String = when (mode) {
        ExtensionMode.AUTO -> "AUTO"
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "NIGHT"
        ExtensionMode.BOKEH -> "BOKEH"
        ExtensionMode.FACE_RETOUCH -> "FACE_RETOUCH"
        else -> "STANDARD"
    }

    private fun configureExposure(boundCamera: Camera) {
        val state = boundCamera.cameraInfo.exposureState
        exposureMin = state.exposureCompensationRange.lower
        exposureMax = state.exposureCompensationRange.upper
        exposureIndex = state.exposureCompensationIndex.coerceIn(exposureMin, exposureMax)
        exposureStep = state.exposureCompensationStep.toFloat()
        exposureAvailable = exposureMax > exposureMin && exposureStep > 0f
        updateExposureLabel()
    }

    private fun applyExposureIndex(index: Int) {
        exposureIndex = index.coerceIn(exposureMin, exposureMax)
        camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
        updateExposureLabel()
    }

    private fun updateExposureLabel() {
        val ev = exposureIndex * exposureStep
        val value = if (abs(ev) < 0.05f) "0.0" else String.format(Locale.US, "%+.1f", ev)
        binding.exposureGestureIndicator.text = "EV  $value"
    }

    private fun showExposureIndicator() {
        binding.exposureGestureIndicator.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 1f
        }
    }

    private fun hideExposureIndicator() {
        if (binding.exposureGestureIndicator.visibility != View.VISIBLE) return
        binding.exposureGestureIndicator.animate()
            .alpha(0f)
            .setStartDelay(450)
            .setDuration(180)
            .withEndAction { binding.exposureGestureIndicator.visibility = View.GONE }
            .start()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun playHaptic(cue: HapticCue, target: View = binding.root) {
        val feedback = when (cue) {
            HapticCue.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticCue.SELECT -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticCue.TICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            HapticCue.TOGGLE_ON -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.TOGGLE_ON
            } else {
                HapticFeedbackConstants.CONTEXT_CLICK
            }
            HapticCue.TOGGLE_OFF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.TOGGLE_OFF
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            HapticCue.CONFIRM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            HapticCue.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            HapticCue.FOCUS -> HapticFeedbackConstants.CLOCK_TICK
            HapticCue.LOCK -> HapticFeedbackConstants.LONG_PRESS
            HapticCue.GESTURE_START -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.GESTURE_START
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            HapticCue.GESTURE_END -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.GESTURE_END
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            HapticCue.SHUTTER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        }
        target.performHapticFeedback(feedback)
        if (cue == HapticCue.SHUTTER) {
            target.postDelayed(
                { target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) },
                42L
            )
        }
    }

    private fun selectLens(mode: LensMode) {
        if (mode == LensMode.ULTRA_WIDE && !rearUltraWideAvailable) return
        if (mode == LensMode.CROP_2X && !rearCrop2xAvailable) return
        playHaptic(HapticCue.SELECT)
        pendingRearLens = mode
        val activePipelineCannotReachZoom = when (mode) {
            LensMode.ULTRA_WIDE -> minZoomRatio >= 0.95f
            LensMode.CROP_2X -> usingFrontCamera || maxZoomRatio < 2f
            else -> false
        }
        val requiresStandardPipeline =
            mode == LensMode.ULTRA_WIDE || activePipelineCannotReachZoom
        if (usingFrontCamera || forceStandardCamera != requiresStandardPipeline) {
            usingFrontCamera = false
            forceStandardCamera = requiresStandardPipeline
            flashMode = ImageCapture.FLASH_MODE_OFF
            updateFlashButton()
            bindCamera()
            return
        }
        applyRearLens(camera ?: return, mode)
    }

    private fun applyRearLens(activeCamera: Camera, mode: LensMode) {
        val zoom = when (mode) {
            LensMode.ULTRA_WIDE -> {
                if (!rearUltraWideAvailable || minZoomRatio >= 0.95f) return
                max(minZoomRatio, 0.6f)
            }
            LensMode.WIDE -> 1f.coerceIn(minZoomRatio, maxZoomRatio)
            LensMode.CROP_2X -> {
                if (!rearCrop2xAvailable || maxZoomRatio < 2f) return
                2f
            }
            LensMode.CUSTOM -> return
        }
        lensMode = mode
        activeCamera.cameraControl.setZoomRatio(zoom)
        updateLensSelection(zoom)
    }

    private fun toggleCameraFacing() {
        playHaptic(HapticCue.SELECT)
        usingFrontCamera = !usingFrontCamera
        forceStandardCamera = false
        pendingRearLens = LensMode.WIDE
        lensMode = if (usingFrontCamera) LensMode.CUSTOM else LensMode.WIDE
        flashMode = ImageCapture.FLASH_MODE_OFF
        updateFlashButton()
        if (usingFrontCamera) updateFrontCameraSelection()
        else updateLensSelection(1f)
        bindCamera()
    }

    private fun updateFrontCameraSelection() {
        setLensSelected(binding.lensUltraWideButton, false)
        setLensSelected(binding.lensWideButton, false)
        setLensSelected(binding.lensCropButton, false)
        binding.frontCameraButton.setBackgroundColor(Color.TRANSPARENT)
        binding.frontCameraButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.frontCameraButton.alpha = 1f
        binding.frontCameraButton.contentDescription = "Switch to the rear camera"
    }

    private fun updateLensSelection(zoom: Float) {
        val mode = when {
            abs(zoom - max(minZoomRatio, 0.6f)) < 0.08f && minZoomRatio < 0.95f -> LensMode.ULTRA_WIDE
            abs(zoom - 1f) < 0.08f -> LensMode.WIDE
            abs(zoom - 2f) < 0.12f -> LensMode.CROP_2X
            else -> LensMode.CUSTOM
        }
        lensMode = mode
        setLensSelected(binding.lensUltraWideButton, mode == LensMode.ULTRA_WIDE)
        setLensSelected(binding.lensWideButton, mode == LensMode.WIDE)
        setLensSelected(binding.lensCropButton, mode == LensMode.CROP_2X)
        binding.frontCameraButton.setBackgroundColor(Color.TRANSPARENT)
        binding.frontCameraButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.frontCameraButton.alpha = 0.72f
        binding.frontCameraButton.contentDescription = "Switch to the front camera"
        binding.lensUltraWideButton.isEnabled = rearUltraWideAvailable
        binding.lensUltraWideButton.alpha = if (rearUltraWideAvailable) 1f else 0f
        binding.lensCropButton.isEnabled = rearCrop2xAvailable
        binding.lensCropButton.alpha = if (rearCrop2xAvailable) 1f else 0f
    }

    @ExperimentalCamera2Interop
    private fun focusAt(x: Float, y: Float, locked: Boolean) {
        val activeCamera = camera ?: return
        playHaptic(if (locked) HapticCue.LOCK else HapticCue.FOCUS)
        if (!locked) setAeLock(false)
        val pointFactory = binding.previewView.meteringPointFactory
        val focusPoint = pointFactory.createPoint(x, y, FOCUS_METERING_SIZE)
        val exposurePoint = pointFactory.createPoint(x, y, EXPOSURE_METERING_SIZE)
        val builder = FocusMeteringAction.Builder(focusPoint, FocusMeteringAction.FLAG_AF)
            .addPoint(exposurePoint, FocusMeteringAction.FLAG_AE)
        if (locked) builder.disableAutoCancel() else builder.setAutoCancelDuration(3, TimeUnit.SECONDS)
        val focusFuture = activeCamera.cameraControl.startFocusAndMetering(builder.build())
        if (locked) {
            focusFuture.addListener(
                { setAeLock(true) },
                ContextCompat.getMainExecutor(this)
            )
        }
        focusLocked = locked
        binding.focusLockBadge.visibility = if (locked) View.VISIBLE else View.GONE
        showFocusRing(x, y, locked)
    }

    @ExperimentalCamera2Interop
    private fun setAeLock(locked: Boolean) {
        val control = camera?.cameraControl ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            .build()
        runCatching {
            Camera2CameraControl.from(control).addCaptureRequestOptions(options)
        }.onFailure {
            Log.d(CAMERA_LOG_TAG, "AE lock not exposed by active OEM extension", it)
        }
    }

    private fun showFocusRing(x: Float, y: Float, locked: Boolean) {
        binding.focusRing.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 1f
            scaleX = 1.3f
            scaleY = 1.3f
            this.x = binding.previewView.x + x - width / 2f
            this.y = binding.previewView.y + y - height / 2f
            animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
                if (!locked) {
                    animate().alpha(0f).setStartDelay(850).setDuration(300)
                        .withEndAction { visibility = View.GONE }.start()
                }
            }.start()
        }
    }

    private fun capturePhoto() {
        if (!ensureApiKey()) return
        if (!ensureBudgetAvailable(photoQuality)) return
        val capture = imageCapture ?: return
        // The Activity intentionally stays locked so controls do not move. Apply the
        // physical device orientation to the capture at the last possible moment.
        capture.targetRotation = cameraTargetRotation
        val capturedLook = photoLook
        val capturedQuality = photoQuality
        binding.shutterButton.isEnabled = false
        showImmediateShutterFeedback()
        showCaptureFlash()

        val file = File(
            File(filesDir, "pending").apply { mkdirs() },
            "capture_${timestamp()}.jpg"
        )
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    prepareCapturedPhoto(file, capturedLook, capturedQuality)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        binding.shutterButton.isEnabled = true
                        showError("Capture failed", exception.message ?: "Camera error")
                    }
                }
            }
        )
    }

    private fun prepareCapturedPhoto(source: File, look: PhotoLook, quality: PhotoQuality) {
        try {
            val preview = if (look == PhotoLook.BLACK_AND_WHITE) {
                ImageUtils.createBlackAndWhiteCopy(this, source)
            } else source
            runOnUiThread {
                binding.shutterButton.isEnabled = true
                if (enqueueEnhancement(source, preview, look, quality)) {
                    showPendingThumbnail(preview)
                } else {
                    deleteTemporaryCaptureFiles(source, preview)
                }
            }
        } catch (error: Exception) {
            source.delete()
            runOnUiThread {
                binding.shutterButton.isEnabled = true
                showError("Photograph unavailable", error.message ?: "Read error")
            }
        }
    }

    private fun enqueueEnhancement(
        source: File,
        preview: File,
        look: PhotoLook,
        quality: PhotoQuality,
        originalGalleryUri: Uri? = null,
        useCaptureTimeContext: Boolean = true
    ): Boolean {
        if (keyStore.load().isNullOrBlank()) return false
        val reservation = budgetStore.tryReserve(quality) ?: run {
            showBudgetLimitReached()
            return false
        }
        val jobId = UUID.randomUUID().toString()
        val job = PendingGeneration(
            id = jobId,
            sourcePath = source.absolutePath,
            previewPath = preview.absolutePath,
            look = look,
            quality = quality,
            createdAt = System.currentTimeMillis(),
            captureZoneId = ZoneId.systemDefault().id,
            status = GenerationStatus.QUEUED,
            reservation = reservation,
            originalGalleryUri = originalGalleryUri?.toString(),
            useCaptureTimeContext = useCaptureTimeContext
        )
        generationStore.add(job)
        refreshPendingGenerations()
        return try {
            GenerationWorkScheduler.enqueue(this)
            updateBudgetIndicator()
            updateProcessingIndicators()
            true
        } catch (error: Exception) {
            generationStore.remove(job.id)
            budgetStore.release(job, status = "enqueue_failed")
            refreshPendingGenerations()
            Toast.makeText(
                this,
                "Unable to start NXT development in the background",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun showPendingThumbnail(file: File) {
        val fromHidden = binding.latestThumbnail.visibility != View.VISIBLE
        binding.latestThumbnail.setImageURI(Uri.fromFile(file))
        binding.latestThumbnail.visibility = View.VISIBLE
        binding.importIcon.visibility = View.GONE
        updateProcessingIndicators()
        animateThumbnail(fromHidden = fromHidden)
    }

    private fun showImmediateShutterFeedback() {
        playHaptic(HapticCue.SHUTTER, binding.shutterButton)
        binding.shutterButton.animate().cancel()
        binding.shutterButton.animate()
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(65L)
            .withEndAction {
                binding.shutterButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(115L)
                    .start()
            }
            .start()

        binding.previewView.bitmap?.let { frame ->
            val fromHidden = binding.latestThumbnail.visibility != View.VISIBLE
            binding.latestThumbnail.setImageBitmap(frame)
            binding.latestThumbnail.visibility = View.VISIBLE
            binding.importIcon.visibility = View.GONE
            animateThumbnail(fromHidden)
            if (isFoldLayout) {
                binding.foldPreviewImage.setImageBitmap(frame)
                binding.foldProcessingStatus.setText(R.string.nxt_shot_acquired)
            }
        }
    }

    private fun deleteTemporaryCaptureFiles(source: File, preview: File) {
        source.delete()
        if (preview != source) preview.delete()
    }

    private fun animateThumbnail(fromHidden: Boolean) {
        binding.latestThumbnail.apply {
            animate().cancel()
            if (!isAppResumed) {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                return
            }
            if (fromHidden) {
                alpha = 0f
                scaleX = 0.82f
                scaleY = 0.82f
            } else {
                alpha = 1f
                scaleX = 0.94f
                scaleY = 0.94f
            }
            animate()
                .alpha(1f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(170)
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                }
                .start()
        }
    }

    private fun viewerItems(): List<ViewerItem> =
        pendingGenerations.sortedByDescending { it.createdAt }.map(ViewerItem::Pending) +
            appPhotos.map(ViewerItem::Saved)

    private fun currentViewerItem(): ViewerItem? = viewerItems().getOrNull(currentPhotoIndex)

    private fun refreshPendingGenerations() {
        pendingGenerations.clear()
        pendingGenerations += generationStore.list()
    }

    private fun refreshGenerationState(completedUri: Uri? = null) {
        val visibleId = currentViewerItem()?.stableId
        refreshPendingGenerations()
        updateBudgetIndicator()
        updateLibraryThumbnail()
        loadPhotoLibrary(completedUri, visibleId)
        updateProcessingIndicators()
    }

    private fun cleanupOrphanPendingFiles() {
        val tracked = generationStore.list().flatMap { job ->
            listOfNotNull(job.sourcePath, job.previewPath, job.resultPath)
        }.toSet()
        File(filesDir, "pending").listFiles()?.forEach { file ->
            if (file.absolutePath !in tracked) file.delete()
        }
    }

    private fun loadPhotoLibrary(preferredUri: Uri? = null, visibleIdOverride: String? = null) {
        val visibleId = visibleIdOverride ?: currentViewerItem()?.stableId
        cameraExecutor.execute {
            val loaded = runCatching { GallerySaver.listEditedPhotos(this) }.getOrDefault(emptyList())
            loaded.forEach { photo ->
                if (importLinkStore.originalFor(photo.uri) != null &&
                    !importLinkStore.isDateNormalized(photo.uri)
                ) {
                    GallerySaver.markImportedResultAsNew(
                        this,
                        photo.uri,
                        photo.dateAddedSeconds * 1_000L
                    )
                    importLinkStore.markDateNormalized(photo.uri)
                }
            }
            runOnUiThread {
                refreshPendingGenerations()
                appPhotos.clear()
                appPhotos.addAll(loaded)
                updateLibraryThumbnail()
                if (binding.resultOverlay.visibility == View.VISIBLE) {
                    val items = viewerItems()
                    if (items.isEmpty()) {
                        returnToCamera()
                    } else {
                        val target = preferredUri?.let { uri ->
                            items.indexOfFirst {
                                it is ViewerItem.Saved && it.photo.uri == uri
                            }.takeIf { it >= 0 }
                        } ?: visibleId?.let { id ->
                            items.indexOfFirst { it.stableId == id }.takeIf { it >= 0 }
                        } ?: 0
                        showPhotoAt(target)
                    }
                }
                if (binding.rollTimelineOverlay.visibility == View.VISIBLE) {
                    renderRollTimeline()
                }
                if (preferredUri != null) animateThumbnail(fromHidden = false)
                updateProcessingIndicators()
            }
        }
    }

    private fun updateLibraryThumbnail() {
        val pending = pendingGenerations.maxByOrNull { it.createdAt }
        val latest = appPhotos.firstOrNull()
        when {
            pending != null -> binding.latestThumbnail.setImageURI(Uri.fromFile(File(pending.previewPath)))
            latest != null -> binding.latestThumbnail.setImageURI(latest.uri)
            else -> binding.latestThumbnail.setImageDrawable(null)
        }
        val hasContent = pending != null || latest != null
        binding.latestThumbnail.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.importIcon.visibility = if (hasContent) View.GONE else View.VISIBLE
        updateFoldPreviewPanel()
    }

    private fun updateFoldPreviewPanel() {
        if (!isFoldLayout) {
            binding.foldProcessingAnimation.stop()
            binding.foldGenerationEffect.stop()
            return
        }
        val pending = pendingGenerations.maxByOrNull { it.createdAt }
        val latest = appPhotos.firstOrNull()
        when {
            pending != null -> {
                binding.foldPreviewImage.setImageURI(Uri.fromFile(File(pending.previewPath)))
                val isPaused = pending.status == GenerationStatus.PAUSED
                binding.foldProcessingStatus.setText(
                    if (isPaused) R.string.nxt_processing_paused else R.string.nxt_processing
                )
                if (isAppResumed && !isPaused) {
                    binding.foldProcessingAnimation.start()
                    binding.foldGenerationEffect.start()
                } else {
                    binding.foldProcessingAnimation.stop()
                    binding.foldGenerationEffect.stop()
                }
            }
            latest != null -> {
                binding.foldPreviewImage.setImageURI(latest.uri)
                binding.foldProcessingStatus.setText(R.string.nxt_last_shot)
                binding.foldProcessingAnimation.stop()
                binding.foldGenerationEffect.stop()
            }
            else -> {
                binding.foldPreviewImage.setImageDrawable(null)
                binding.foldProcessingStatus.setText(R.string.nxt_ready)
                binding.foldProcessingAnimation.stop()
                binding.foldGenerationEffect.stop()
            }
        }
    }

    private fun openPhotoLibrary() {
        stopCameraSession()
        binding.monthlyBudgetBar.visibility = View.GONE
        binding.frontCameraButton.visibility = View.GONE
        binding.rollTimelineOverlay.visibility = View.VISIBLE
        binding.rollTimelineOverlay.bringToFront()
        renderRollTimeline()
        requestLegacyRollAccessIfNeeded()
        updateRotationSensorRegistration()
        updateProcessingIndicators()
    }

    @Suppress("DEPRECATION")
    private fun requestLegacyRollAccessIfNeeded() {
        val legacyPackageInstalled = runCatching {
            packageManager.getApplicationInfo(LEGACY_PACKAGE_ID, 0)
        }.isSuccess
        if (!legacyPackageInstalled) return

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            legacyRollPermissionLauncher.launch(permission)
        }
    }

    private fun choosePhotoToImport() {
        playHaptic(HapticCue.TAP)
        if (keyStore.load().isNullOrBlank()) {
            closeRollTimeline()
            showSettings(firstRun = true)
            Toast.makeText(this, "Enter your OpenAI key first.", Toast.LENGTH_SHORT).show()
            return
        }
        importPhotoLauncher.launch(arrayOf("image/*"))
    }

    private fun showImportEditor(file: File, originalUri: Uri) {
        pendingImportFile?.takeIf { it != file }?.delete()
        pendingImportFile = file
        pendingImportOriginalUri = originalUri
        importLook = photoLook
        importQuality = photoQuality
        binding.importPreviewImage.setImageURI(Uri.fromFile(file))
        binding.importEditorOverlay.visibility = View.VISIBLE
        binding.importEditorOverlay.bringToFront()
        updateImportSelections()
        playHaptic(HapticCue.CONFIRM)
    }

    private fun setImportLook(look: PhotoLook) {
        if (importLook != look) playHaptic(HapticCue.SELECT)
        importLook = look
        updateImportSelections()
    }

    private fun setImportQuality(quality: PhotoQuality) {
        if (importQuality != quality) {
            playHaptic(if (quality == PhotoQuality.MAX) HapticCue.TOGGLE_ON else HapticCue.TOGGLE_OFF)
        }
        importQuality = quality
        updateImportSelections()
    }

    private fun updateImportSelections() {
        setLookSelected(binding.importColorLookButton, importLook == PhotoLook.COLOR)
        setLookSelected(
            binding.importBlackAndWhiteLookButton,
            importLook == PhotoLook.BLACK_AND_WHITE
        )
        setLookSelected(binding.importPortraitLookButton, importLook == PhotoLook.PORTRAIT)
        setLookSelected(binding.importHighQualityButton, importQuality == PhotoQuality.HIGH)
        setLookSelected(binding.importMaxQualityButton, importQuality == PhotoQuality.MAX)
        binding.importPreviewImage.colorFilter = if (importLook == PhotoLook.BLACK_AND_WHITE) {
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        } else {
            null
        }
    }

    private fun enqueueImportedPhoto() {
        val source = pendingImportFile ?: return
        val originalUri = pendingImportOriginalUri ?: return
        if (!ensureBudgetAvailable(importQuality)) return
        binding.importDevelopButton.isEnabled = false
        val queued = enqueueEnhancement(
            source = source,
            preview = source,
            look = importLook,
            quality = importQuality,
            originalGalleryUri = originalUri,
            useCaptureTimeContext = false
        )
        binding.importDevelopButton.isEnabled = true
        if (!queued) return

        pendingImportFile = null
        pendingImportOriginalUri = null
        binding.importPreviewImage.setImageDrawable(null)
        binding.importPreviewImage.colorFilter = null
        binding.importEditorOverlay.visibility = View.GONE
        renderRollTimeline()
        updateProcessingIndicators()
        playHaptic(HapticCue.SHUTTER, binding.importDevelopButton)
    }

    private fun cancelImportEditor() {
        playHaptic(HapticCue.TAP)
        pendingImportFile?.delete()
        pendingImportFile = null
        pendingImportOriginalUri = null
        binding.importPreviewImage.setImageDrawable(null)
        binding.importPreviewImage.colorFilter = null
        binding.importEditorOverlay.visibility = View.GONE
        binding.rollTimelineOverlay.bringToFront()
    }

    private fun renderRollTimeline() {
        val container = binding.timelineMonthsContainer
        timelineLoadingViews.forEach { it.stop() }
        timelineLoadingViews.clear()
        timelineMonthAnchors.clear()
        container.removeAllViews()
        val items = viewerItems()
        val pendingCount = items.count { it is ViewerItem.Pending }
        binding.timelineSubtitle.text = buildString {
            append(items.size)
            append(if (items.size == 1) " photograph" else " photographs")
            append(" · nxt roll")
            if (pendingCount > 0) {
                append(" · ")
                append(pendingCount)
                append(" developing")
            }
        }
        val summaries = budgetStore.monthlySummaries()
        val grouped = items.groupBy(::viewerItemMonth)
            .toSortedMap(compareByDescending { it })
        grouped.forEach { (monthKey, monthItems) ->
            val monthTitle = budgetMonthLabel(monthKey).lowercase(Locale.US)
            val summary = summaries[monthKey]
            val monthSubtitle = buildString {
                append(monthItems.size)
                append(if (monthItems.size == 1) " frame" else " frames")
                summary?.let {
                    append(" · $")
                    append(formatUsd(it.spentUsd))
                    it.averageMeasuredCostUsd?.let { average ->
                        append(" · avg $")
                        append(formatUsd(average))
                    }
                }
            }
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(78f).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            }
            heading.addView(TextView(this).apply {
                text = monthTitle
                setTextColor(Color.WHITE)
                textSize = 36f
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                includeFontPadding = false
                letterSpacing = -0.02f
                maxLines = 1
            })
            heading.addView(TextView(this).apply {
                text = monthSubtitle
                setTextColor(getColor(R.color.muted_white))
                textSize = 9f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(0, dp(3f).toInt(), 0, 0)
            })
            container.addView(heading)
            timelineMonthAnchors += TimelineMonthAnchor(
                key = monthKey,
                header = heading,
                title = monthTitle,
                subtitle = monthSubtitle
            )

            val grid = GridLayout(this).apply {
                columnCount = 3
                alignmentMode = GridLayout.ALIGN_BOUNDS
                useDefaultMargins = false
            }
            val availableWidth = container.width.takeIf { it > 0 }
                ?: binding.root.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val tileSize = ((availableWidth - dp(6f)) / 3f).toInt()
            monthItems.forEach { item ->
                grid.addView(createTimelineTile(item, tileSize))
            }
            container.addView(grid)
        }
        container.post { updateStickyMonthHeader(binding.timelineScroll.scrollY) }
    }

    private fun updateStickyMonthHeader(scrollY: Int) {
        if (timelineMonthAnchors.isEmpty() || binding.rollTimelineOverlay.visibility != View.VISIBLE) {
            binding.stickyMonthHeader.visibility = View.INVISIBLE
            return
        }
        val currentIndex = timelineMonthAnchors.indexOfLast { it.header.top <= scrollY + 1 }
            .coerceAtLeast(0)
        val current = timelineMonthAnchors[currentIndex]
        if (binding.stickyMonthHeader.tag != current.key) {
            binding.stickyMonthHeader.tag = current.key
            binding.stickyMonthTitle.text = current.title
            binding.stickyMonthSubtitle.text = current.subtitle
        }
        binding.stickyMonthHeader.visibility = View.VISIBLE
        val nextHeader = timelineMonthAnchors.getOrNull(currentIndex + 1)?.header
        val distanceToNext = nextHeader?.let { it.top - scrollY } ?: Int.MAX_VALUE
        binding.stickyMonthHeader.translationY = if (
            distanceToNext < binding.stickyMonthHeader.height
        ) {
            (distanceToNext - binding.stickyMonthHeader.height).toFloat()
        } else {
            0f
        }
    }

    private fun viewerItemMonth(item: ViewerItem): String {
        val timestamp = when (item) {
            is ViewerItem.Pending -> item.generation.createdAt
            is ViewerItem.Saved -> item.photo.dateAddedSeconds * 1_000L
        }
        return YearMonth.from(
            java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        ).toString()
    }

    private fun createTimelineTile(item: ViewerItem, tileSize: Int): View {
        val tile = FrameLayout(this).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = tileSize
                height = tileSize
                setMargins(dp(1f).toInt(), dp(1f).toInt(), dp(1f).toInt(), dp(1f).toInt())
            }
            setBackgroundResource(R.drawable.timeline_tile)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            isClickable = true
            isFocusable = true
            stateListAnimator = AnimatorInflater.loadStateListAnimator(
                this@MainActivity,
                R.animator.expressive_press
            )
            setOnClickListener {
                playHaptic(HapticCue.SELECT)
                openTimelineItem(item.stableId)
            }
        }
        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Open photograph from NXT Roll"
        }
        tile.addView(image)
        when (item) {
            is ViewerItem.Pending -> {
                image.setImageURI(Uri.fromFile(File(item.generation.previewPath)))
                val loading = ThumbnailLoadingView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply {
                        gravity = Gravity.CENTER
                    }
                    if (item.generation.status != GenerationStatus.PAUSED) start()
                }
                timelineLoadingViews += loading
                tile.addView(loading)
            }
            is ViewerItem.Saved -> cameraExecutor.execute {
                val bitmap = runCatching {
                    contentResolver.loadThumbnail(item.photo.uri, Size(tileSize, tileSize), null)
                }.getOrNull()
                if (bitmap != null) runOnUiThread {
                    if (image.isAttachedToWindow) image.setImageBitmap(bitmap)
                }
            }
        }
        return tile
    }

    private fun openTimelineItem(stableId: String) {
        val index = viewerItems().indexOfFirst { it.stableId == stableId }
        if (index < 0) return
        viewerOpenedFromTimeline = true
        binding.rollTimelineOverlay.visibility = View.GONE
        binding.resultOverlay.visibility = View.VISIBLE
        binding.resultOverlay.bringToFront()
        showPhotoAt(index)
        updateProcessingIndicators()
    }

    private fun closePhotoViewer() {
        if (!viewerOpenedFromTimeline) {
            returnToCamera()
            return
        }
        binding.resultOverlay.visibility = View.GONE
        binding.resultImage.setImageDrawable(null)
        binding.compareResultButton.visibility = View.GONE
        currentImportedOriginalUri = null
        showingImportedOriginal = false
        binding.generationEffect.stop()
        setResultZoom(1f, animated = false)
        binding.rollTimelineOverlay.visibility = View.VISIBLE
        binding.rollTimelineOverlay.bringToFront()
        renderRollTimeline()
        updateProcessingIndicators()
    }

    private fun closeRollTimeline() {
        viewerOpenedFromTimeline = false
        binding.rollTimelineOverlay.visibility = View.GONE
        timelineLoadingViews.forEach { it.stop() }
        timelineLoadingViews.clear()
        timelineMonthAnchors.clear()
        binding.stickyMonthHeader.visibility = View.INVISIBLE
        binding.stickyMonthHeader.translationY = 0f
        binding.stickyMonthHeader.tag = null
        binding.timelineMonthsContainer.removeAllViews()
        binding.frontCameraButton.visibility = View.VISIBLE
        binding.monthlyBudgetBar.visibility = View.VISIBLE
        updateRotationSensorRegistration()
        resumeCameraSession()
    }

    private fun showPhotoAt(index: Int) {
        val items = viewerItems()
        val item = items.getOrNull(index) ?: return
        setResultZoom(1f, animated = false)
        currentPhotoIndex = index
        showingImportedOriginal = false
        currentImportedOriginalUri = null
        when (item) {
            is ViewerItem.Pending -> {
                binding.resultImage.setImageURI(Uri.fromFile(File(item.generation.previewPath)))
                binding.resultImage.contentDescription = "Original photograph in NXT development"
                binding.compareResultButton.visibility = View.GONE
                updateResultActions(
                    canShare = false,
                    canDelete = item.generation.status == GenerationStatus.PAUSED
                )
            }

            is ViewerItem.Saved -> {
                binding.resultImage.setImageURI(item.photo.uri)
                binding.resultImage.contentDescription = getString(R.string.result_ai)
                currentImportedOriginalUri = readableImportedOriginal(item.photo.uri)
                binding.compareResultButton.visibility = if (currentImportedOriginalUri != null) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                binding.compareResultButton.text = "ORIGINAL"
                updateResultActions(canShare = true, canDelete = true)
            }
        }
        binding.resultPosition.text = "${index + 1} / ${items.size}"
        binding.resultPosition.visibility = View.VISIBLE
        updateProcessingIndicators()
    }

    private fun readableImportedOriginal(resultUri: Uri): Uri? {
        val original = importLinkStore.originalFor(resultUri) ?: return null
        return runCatching {
            contentResolver.openAssetFileDescriptor(original, "r")?.use { original }
        }.getOrNull()
    }

    private fun toggleImportedComparison() {
        val item = currentViewerItem() as? ViewerItem.Saved ?: return
        val original = currentImportedOriginalUri ?: return
        showingImportedOriginal = !showingImportedOriginal
        setResultZoom(1f, animated = false)
        if (showingImportedOriginal) {
            binding.resultImage.setImageURI(original)
            binding.resultImage.contentDescription = "Imported original photograph"
            binding.compareResultButton.text = "RESULT"
        } else {
            binding.resultImage.setImageURI(item.photo.uri)
            binding.resultImage.contentDescription = getString(R.string.result_ai)
            binding.compareResultButton.text = "ORIGINAL"
        }
        playHaptic(HapticCue.SELECT)
    }

    private fun updateResultActions(canShare: Boolean, canDelete: Boolean) {
        binding.deleteResultButton.visibility = if (canDelete) View.VISIBLE else View.INVISIBLE
        binding.shareResultButton.visibility = if (canShare) View.VISIBLE else View.INVISIBLE
    }

    private fun updateProcessingIndicators() {
        if (!isAppResumed) {
            binding.thumbnailLoadingBorder.stop()
            binding.resultProcessingAnimation.stop()
            binding.resultProcessingIndicator.visibility = View.GONE
            binding.generationEffect.stop()
            return
        }

        if (binding.resultOverlay.visibility == View.VISIBLE) {
            binding.thumbnailLoadingBorder.stop()
            val isPendingPhoto = currentViewerItem() is ViewerItem.Pending
            if (isPendingPhoto) {
                binding.resultProcessingIndicator.visibility = View.VISIBLE
                val pending = (currentViewerItem() as ViewerItem.Pending).generation
                val isPaused = pending.status == GenerationStatus.PAUSED
                binding.resultProcessingText.text = if (isPaused) {
                    "NXT DEVELOPMENT ON HOLD"
                } else {
                    "NXT DEVELOPMENT IN PROGRESS"
                }
                if (isPaused) {
                    binding.resultProcessingAnimation.stop()
                    binding.generationEffect.stop()
                } else {
                    binding.resultProcessingAnimation.start()
                    binding.generationEffect.start()
                }
            } else {
                binding.resultProcessingAnimation.stop()
                binding.resultProcessingIndicator.visibility = View.GONE
                binding.generationEffect.stop()
            }
        } else {
            binding.resultProcessingAnimation.stop()
            binding.resultProcessingIndicator.visibility = View.GONE
            binding.generationEffect.stop()
            if (pendingJobs > 0) binding.thumbnailLoadingBorder.start()
            else binding.thumbnailLoadingBorder.stop()
        }
        updateFoldPreviewPanel()
    }

    private fun setResultZoom(scale: Float, animated: Boolean) {
        resultScale = scale
        resultTranslationX = 0f
        resultTranslationY = 0f
        applyResultTransform(animated)
    }

    private fun clampResultTranslation() {
        val image = binding.resultImage
        val drawable = image.drawable ?: return
        if (resultScale <= 1f || image.width <= 0 || image.height <= 0) {
            resultTranslationX = 0f
            resultTranslationY = 0f
            return
        }
        val drawableWidth = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val drawableHeight = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val fittedScale = min(image.width / drawableWidth, image.height / drawableHeight)
        val displayedWidth = drawableWidth * fittedScale * resultScale
        val displayedHeight = drawableHeight * fittedScale * resultScale
        val maxX = max(0f, (displayedWidth - image.width) / 2f)
        val maxY = max(0f, (displayedHeight - image.height) / 2f)
        resultTranslationX = resultTranslationX.coerceIn(-maxX, maxX)
        resultTranslationY = resultTranslationY.coerceIn(-maxY, maxY)
    }

    private fun applyResultTransform(animated: Boolean) {
        binding.resultImage.animate().cancel()
        binding.resultImage.pivotX = binding.resultImage.width / 2f
        binding.resultImage.pivotY = binding.resultImage.height / 2f
        if (animated) {
            binding.resultImage.animate()
                .scaleX(resultScale)
                .scaleY(resultScale)
                .translationX(resultTranslationX)
                .translationY(resultTranslationY)
                .setDuration(190)
                .start()
        } else {
            binding.resultImage.scaleX = resultScale
            binding.resultImage.scaleY = resultScale
            binding.resultImage.translationX = resultTranslationX
            binding.resultImage.translationY = resultTranslationY
        }
    }

    private fun shareResult() {
        val uri = (currentViewerItem() as? ViewerItem.Saved)?.photo?.uri ?: return
        playHaptic(HapticCue.TAP)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share NXT-CAM photograph"))
    }

    private fun confirmDeleteCurrentPhoto() {
        playHaptic(HapticCue.SELECT)
        when (val item = currentViewerItem()) {
            is ViewerItem.Saved -> AlertDialog.Builder(this)
                .setTitle("Delete this photograph?")
                .setMessage("The NXT-CAM photograph will be permanently removed from the phone.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE") { _, _ -> deletePhoto(item.photo) }
                .show()
            is ViewerItem.Pending -> AlertDialog.Builder(this)
                .setTitle("Delete this frame?")
                .setMessage("NXT development will be cancelled and temporary files will be deleted.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE") { _, _ -> deletePendingGeneration(item.generation) }
                .show()
            null -> Unit
        }
    }

    private fun deletePendingGeneration(job: PendingGeneration) {
        playHaptic(HapticCue.CONFIRM)
        generationStore.remove(job.id)
        budgetStore.release(job, status = "cancelled")
        File(job.sourcePath).delete()
        if (job.previewPath != job.sourcePath) File(job.previewPath).delete()
        job.resultPath?.let { File(it).delete() }
        refreshGenerationState()
    }

    private fun deletePhoto(photo: GallerySaver.SavedPhoto) {
        val removedIndex = currentPhotoIndex
        cameraExecutor.execute {
            val deleted = runCatching { GallerySaver.delete(this, photo.uri) }.getOrDefault(false)
            runOnUiThread {
                if (!deleted) {
                    showError("Delete failed", "The photograph could not be deleted.")
                    return@runOnUiThread
                }
                playHaptic(HapticCue.CONFIRM)
                importLinkStore.remove(photo.uri)
                appPhotos.removeAll { it.uri == photo.uri }
                updateLibraryThumbnail()
                val items = viewerItems()
                if (items.isEmpty()) {
                    currentPhotoIndex = -1
                    returnToCamera()
                } else {
                    showPhotoAt(removedIndex.coerceAtMost(items.lastIndex))
                }
            }
        }
    }

    private fun returnToCamera() {
        viewerOpenedFromTimeline = false
        binding.rollTimelineOverlay.visibility = View.GONE
        timelineLoadingViews.forEach { it.stop() }
        timelineLoadingViews.clear()
        binding.resultOverlay.visibility = View.GONE
        binding.frontCameraButton.visibility = View.VISIBLE
        binding.monthlyBudgetBar.visibility = View.VISIBLE
        binding.resultImage.setImageDrawable(null)
        binding.compareResultButton.visibility = View.GONE
        currentImportedOriginalUri = null
        showingImportedOriginal = false
        binding.generationEffect.stop()
        setResultZoom(1f, animated = false)
        updateProcessingIndicators()
        updateRotationSensorRegistration()
        resumeCameraSession()
    }

    private fun ensureApiKey(): Boolean {
        if (!keyStore.load().isNullOrBlank()) return true
        showSettings(firstRun = true)
        return false
    }

    private fun ensureBudgetAvailable(quality: PhotoQuality): Boolean {
        if (budgetStore.canReserve(quality)) return true
        showBudgetLimitReached()
        return false
    }

    private fun showBudgetLimitReached() {
        val snapshot = budgetStore.snapshot()
        val limit = snapshot.limitUsd ?: return
        playHaptic(HapticCue.REJECT)
        AlertDialog.Builder(this)
            .setTitle("Roll complete")
            .setMessage(
                "You have used every available frame in this NXT Roll. " +
                    "Recorded spend: $${formatUsd(snapshot.spentUsd)} of " +
                    "$${formatUsd(limit)}. You can set a new limit in Settings."
            )
            .setPositiveButton("SETTINGS") { _, _ -> showSettings(firstRun = false) }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showCaptureFlash() {
        binding.captureFlash.animate().cancel()
        binding.captureFlash.alpha = 0f
        binding.captureFlash.animate().alpha(0.68f).setDuration(55).withEndAction {
            binding.captureFlash.animate().alpha(0f).setDuration(130).start()
        }.start()
    }

    private fun showSettings(firstRun: Boolean) {
        if (settingsOpen) return
        stopCameraSession()
        updateSettingsContent()
        settingsOpen = true
        binding.settingsOverlay.animate().cancel()
        binding.settingsOverlay.visibility = View.VISIBLE
        binding.settingsOverlay.bringToFront()
        binding.settingsOverlay.alpha = 0f
        binding.settingsOverlay.translationY = dp(12f)
        binding.settingsOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(170L)
            .start()
        updateRotationSensorRegistration()
        if (firstRun) {
            binding.settingsApiKeyInput.post { binding.settingsApiKeyInput.requestFocus() }
        }
    }

    private fun hideSettings(animated: Boolean) {
        settingsOpen = false
        binding.settingsApiKeyInput.clearFocus()
        binding.settingsMonthlyLimitInput.clearFocus()
        binding.settingsOverlay.animate().cancel()
        if (!animated) {
            binding.settingsOverlay.visibility = View.GONE
            binding.settingsOverlay.alpha = 0f
            binding.settingsOverlay.translationY = 0f
            updateRotationSensorRegistration()
            resumeCameraSession()
            return
        }
        binding.settingsOverlay.animate()
            .alpha(0f)
            .translationY(dp(12f))
            .setDuration(140L)
            .withEndAction {
                binding.settingsOverlay.visibility = View.GONE
                binding.settingsOverlay.translationY = 0f
                updateRotationSensorRegistration()
                resumeCameraSession()
            }
            .start()
    }

    private fun updateSettingsButton() {
        binding.settingsButton.setBackgroundResource(android.R.color.transparent)
        binding.settingsButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun updateSettingsContent() {
        val hasKey = !keyStore.load().isNullOrBlank()
        val snapshot = budgetStore.snapshot()
        binding.settingsApiKeyInput.text?.clear()
        binding.settingsApiKeyInput.hint = if (hasKey) "Key saved — enter a new one to replace it" else "sk-…"
        binding.settingsMonthlyLimitInput.setText(
            snapshot.limitUsd?.let(::formatUsd).orEmpty()
        )
        binding.settingsCostStatus.text = buildString {
            append("$")
            append(formatUsdForDisplay(snapshot.spentUsd))
            append(" SPENT THIS MONTH")
            append(" · ")
            append(snapshot.completedShots)
            append(if (snapshot.completedShots == 1) " PHOTO\n" else " PHOTOS\n")
            snapshot.averageMeasuredCostUsd?.let {
                append("$")
                append(formatUsdForDisplay(it))
                append(" AVERAGE COST PER PHOTO")
            } ?: append("— AVERAGE COST PER PHOTO")
        }
        binding.settingsCostStatus.contentDescription =
            "${budgetMonthLabel(snapshot.monthKey)} roll, ${formatUsdForDisplay(snapshot.spentUsd)} dollars spent, " +
                "${snapshot.completedShots} frames, ${budgetStore.costLogCount()} cost log entries"
        binding.settingsClearKeyButton.visibility = if (hasKey) View.VISIBLE else View.GONE
    }

    private fun saveSettings() {
        val hasKey = !keyStore.load().isNullOrBlank()
        val keyValue = binding.settingsApiKeyInput.text.toString().trim()
        val limitText = binding.settingsMonthlyLimitInput.text.toString().trim().replace(',', '.')
        val parsedLimit = limitText.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        if (!hasKey && keyValue.isEmpty()) {
            playHaptic(HapticCue.REJECT)
            binding.settingsApiKeyInput.error = "Enter your OpenAI key"
            return
        }
        if (keyValue.isNotEmpty() && !keyValue.startsWith("sk-")) {
            playHaptic(HapticCue.REJECT)
            binding.settingsApiKeyInput.error = "The key must begin with sk-"
            return
        }
        if (limitText.isNotEmpty() && (parsedLimit == null || parsedLimit <= 0.0)) {
            playHaptic(HapticCue.REJECT)
            binding.settingsMonthlyLimitInput.error = "Enter an amount greater than zero"
            return
        }

        if (keyValue.isNotEmpty()) keyStore.save(keyValue)
        budgetStore.setLimitUsd(parsedLimit)
        generationStore.requeuePausedJobs()
        if (generationStore.list().any {
                it.status == GenerationStatus.QUEUED ||
                    it.status == GenerationStatus.READY_TO_SAVE
            }
        ) {
            GenerationWorkScheduler.enqueue(this)
        }
        updateBudgetIndicator()
        updateSettingsContent()
        hideSettings(animated = true)
        playHaptic(HapticCue.CONFIRM)
        Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
    }

    private fun budgetMonthLabel(monthKey: String): String = runCatching {
        val month = YearMonth.parse(monthKey)
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.US)
        "${name.replaceFirstChar { it.uppercase(Locale.US) }} ${month.year}"
    }.getOrDefault(monthKey)

    private fun formatUsd(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun updateBudgetIndicator() {
        val snapshot = budgetStore.snapshot()
        maybeShowNewRollAnimation(snapshot.monthKey)
        val limit = snapshot.limitUsd
        val today = LocalDate.now()
        val nextReset = YearMonth.from(today).plusMonths(1).atDay(1)
        val daysToReset = ChronoUnit.DAYS.between(today, nextReset).toInt().coerceAtLeast(1)
        binding.monthlyBudgetResetText.text = if (daysToReset == 1) {
            "NEW ROLL IN 1 DAY"
        } else {
            "NEW ROLL IN $daysToReset DAYS"
        }
        if (limit == null) {
            binding.monthlyBudgetText.text = "∞ FRAMES"
            binding.monthlyBudgetText.setTextColor(getColor(R.color.muted_white))
            binding.monthlyBudgetBar.contentDescription =
                "${formatUsdForDisplay(snapshot.spentUsd)} dollars recorded this month, " +
                    "no limit set, $daysToReset days until the monthly reset"
            return
        }

        val estimate = budgetStore.estimatedCostFor(photoQuality)
        val available = (limit - snapshot.spentUsd).coerceAtLeast(0.0)
        val remainingPhotos = (available / estimate).toInt().coerceAtLeast(0)
        binding.monthlyBudgetText.text = if (remainingPhotos == 0) {
            "ROLL FINISHED"
        } else {
            "$remainingPhotos FRAMES"
        }
        binding.monthlyBudgetText.setTextColor(
            if (remainingPhotos == 0) Color.WHITE else getColor(R.color.muted_white)
        )
        binding.monthlyBudgetBar.contentDescription =
            "${formatUsdForDisplay(snapshot.spentUsd)} of ${formatUsdForDisplay(limit)} dollars " +
                "recorded this month, about $remainingPhotos ${photoQuality.label} NXT developments " +
                "available, $daysToReset days until the monthly reset"
    }

    private fun maybeShowNewRollAnimation(monthKey: String) {
        val preferences = getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_ROLL_MONTH, null) == monthKey) return
        preferences.edit().putString(KEY_LAST_ROLL_MONTH, monthKey).apply()
        binding.rollLoadedPill.post {
            binding.rollLoadedPill.animate().cancel()
            binding.rollLoadedPill.visibility = View.VISIBLE
            binding.rollLoadedPill.alpha = 0f
            binding.rollLoadedPill.scaleX = 0.78f
            binding.rollLoadedPill.scaleY = 0.78f
            binding.rollLoadedPill.animate()
                .alpha(1f)
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(190L)
                .withEndAction {
                    binding.rollLoadedPill.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(90L)
                        .withEndAction {
                            binding.rollLoadedPill.animate()
                                .alpha(0f)
                                .setStartDelay(1_350L)
                                .setDuration(220L)
                                .withEndAction { binding.rollLoadedPill.visibility = View.GONE }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    private fun showRollStatus() {
        val snapshot = budgetStore.snapshot()
        val today = LocalDate.now()
        val nextReset = YearMonth.from(today).plusMonths(1).atDay(1)
        val days = ChronoUnit.DAYS.between(today, nextReset).toInt().coerceAtLeast(1)
        val message = buildString {
            append(snapshot.completedShots)
            append(if (snapshot.completedShots == 1) " developed frame" else " developed frames")
            append(" · $")
            append(formatUsd(snapshot.spentUsd))
            append("\nNew roll in ")
            append(days)
            append(if (days == 1) " day" else " days")
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatUsdForDisplay(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun showCameraPermissionError() {
        playHaptic(HapticCue.REJECT)
        AlertDialog.Builder(this)
            .setTitle("Camera unavailable")
            .setMessage(getString(R.string.camera_permission))
            .setPositiveButton("TRY AGAIN") { _, _ -> permissionLauncher.launch(Manifest.permission.CAMERA) }
            .setNegativeButton("CLOSE") { _, _ -> finish() }
            .show()
    }

    private fun showError(title: String, message: String) {
        playHaptic(HapticCue.REJECT)
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> updateNightExtensionForLux(event.values.firstOrNull() ?: return)
            Sensor.TYPE_ROTATION_VECTOR -> {
                val deviceMatrix = FloatArray(9)
                val screenMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(deviceMatrix, event.values)
                val (axisX, axisY) = when (cameraTargetRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(deviceMatrix, axisX, axisY, screenMatrix)
                SensorManager.getOrientation(screenMatrix, orientation)
                binding.shutterLevel.updateLevel(
                    roll = Math.toDegrees(orientation[2].toDouble()).toFloat(),
                    deviceX = screenMatrix[6],
                    deviceY = screenMatrix[7],
                    deviceZ = screenMatrix[8]
                )
            }
        }
    }

    private fun updateNightExtensionForLux(lux: Float) {
        val manager = extensionsManager ?: return
        val selector = if (usingFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val nightAvailable = runCatching {
            manager.isExtensionAvailable(selector, ExtensionMode.NIGHT)
        }.getOrDefault(false)
        if (!nightAvailable) return

        val shouldUseNight = if (nightExtensionRequested) {
            lux < NIGHT_EXIT_LUX
        } else {
            lux < NIGHT_ENTER_LUX
        }
        if (shouldUseNight == nightExtensionRequested) {
            pendingNightExtension = null
            binding.root.removeCallbacks(applyPendingNightExtension)
            return
        }
        if (SystemClock.elapsedRealtime() - lastNightExtensionSwitchMs < NIGHT_SWITCH_COOLDOWN_MS) {
            return
        }
        if (pendingNightExtension == shouldUseNight) return
        pendingNightExtension = shouldUseNight
        binding.root.removeCallbacks(applyPendingNightExtension)
        binding.root.postDelayed(applyPendingNightExtension, NIGHT_SWITCH_DELAY_MS)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
