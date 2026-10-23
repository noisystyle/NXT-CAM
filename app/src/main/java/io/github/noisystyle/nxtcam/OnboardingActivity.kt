package io.github.noisystyle.nxtcam

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.noisystyle.nxtcam.databinding.ActivityOnboardingBinding
import io.github.noisystyle.nxtcam.databinding.ItemOnboardingPageBinding

@ExperimentalCamera2Interop
class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private val isReplay: Boolean by lazy { intent.getBooleanExtra(EXTRA_REPLAY, false) }

    private val pages = listOf(
        OnboardingPage(
            eyebrow = "NXT-CAM / EXPERIMENT 01",
            title = "NOT LESS.\nMORE.",
            body = "A contrarian camera. AI does not hide its intervention: it develops every frame with intent."
        ),
        OnboardingPage(
            eyebrow = "THE ROLL",
            title = "EVERY FRAME\nMATTERS.",
            body = "Fewer frames, chosen better. A cost per shot gives value back to the photographic act."
        ),
        OnboardingPage(
            eyebrow = "NXT DEVELOPMENT",
            title = "CAPTURE.\nTHEN DEVELOP.",
            body = "Color, B&W and Portrait interpret light, color and depth without changing the subject."
        ),
        OnboardingPage(
            eyebrow = "TRY IT FIRST",
            title = "SEE THE\nDIFFERENCE.",
            body = "Import a gallery photo, choose a development and compare before and after. The original stays untouched."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureWindow()

        val hasApiKey = !SecureApiKeyStore(this).load().isNullOrBlank()
        binding.onboardingPager.adapter = OnboardingAdapter(
            pages = pages,
            finalAction = if (hasApiKey) "START" else "SET UP API",
            onContinue = ::continueFrom
        )
        binding.onboardingPager.offscreenPageLimit = pages.size
        binding.onboardingPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                private var previousPage = 0

                override fun onPageSelected(position: Int) {
                    if (position != previousPage) {
                        binding.onboardingRoot.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                            } else {
                                HapticFeedbackConstants.CLOCK_TICK
                            }
                        )
                        previousPage = position
                    }
                }
            }
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = binding.onboardingPager.currentItem
                    if (current > 0) {
                        binding.onboardingPager.setCurrentItem(current - 1, true)
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun continueFrom(position: Int, source: View) {
        if (position < pages.lastIndex) {
            source.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            binding.onboardingPager.setCurrentItem(position + 1, true)
            return
        }

        source.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
        OnboardingState.markCompleted(this)
        if (isReplay) {
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { view, insets ->
            val navigationBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val gestureBottom = insets
                .getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
                .bottom
            val density = resources.displayMetrics.density
            val systemBottom = maxOf(navigationBottom, gestureBottom)
            val safeBottom = maxOf(
                systemBottom,
                (10f * density).toInt()
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
        ViewCompat.requestApplyInsets(binding.onboardingRoot)
        hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowInsetsControllerCompat(window, binding.onboardingRoot).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private data class OnboardingPage(
        val eyebrow: String,
        val title: String,
        val body: String
    )

    private class OnboardingAdapter(
        private val pages: List<OnboardingPage>,
        private val finalAction: String,
        private val onContinue: (Int, View) -> Unit
    ) : RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val binding = ItemOnboardingPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(
                page = pages[position],
                position = position,
                pageCount = pages.size,
                finalAction = finalAction,
                onContinue = onContinue
            )
        }

        override fun getItemCount(): Int = pages.size

        class PageViewHolder(
            private val binding: ItemOnboardingPageBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(
                page: OnboardingPage,
                position: Int,
                pageCount: Int,
                finalAction: String,
                onContinue: (Int, View) -> Unit
            ) {
                binding.pageEyebrow.text = page.eyebrow
                binding.pageTitle.text = page.title
                binding.pageBody.text = page.body
                binding.pageArtwork.showPage(position)
                binding.pageCounter.text = "%02d—%02d".format(position + 1, pageCount)

                val progress = listOf(
                    binding.progressOne,
                    binding.progressTwo,
                    binding.progressThree,
                    binding.progressFour
                )
                progress.forEachIndexed { index, segment ->
                    segment.setBackgroundColor(
                        if (index == position) Color.WHITE else 0x4DFFFFFF
                    )
                }

                val isLast = position == pageCount - 1
                binding.pageCounter.visibility = if (isLast) View.GONE else View.VISIBLE
                binding.pageAction.text = if (isLast) finalAction else "CONTINUE"
                binding.pageAction.gravity = if (isLast) Gravity.CENTER else Gravity.CENTER_VERTICAL
                binding.pageAction.background = if (isLast) {
                    ColorDrawable(Color.rgb(237, 28, 36))
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }

                val params = binding.pageAction.layoutParams as ConstraintLayout.LayoutParams
                if (isLast) {
                    params.endToStart = ConstraintLayout.LayoutParams.UNSET
                    params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    params.topMargin = itemView.resources.displayMetrics.density.times(10f).toInt()
                } else {
                    params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                    params.endToStart = binding.pageCounter.id
                    params.topMargin = 0
                }
                binding.pageAction.layoutParams = params
                binding.pageAction.setOnClickListener { onContinue(position, it) }
                binding.pageAction.contentDescription = if (isLast) {
                    "$finalAction and open NXT-CAM"
                } else {
                    "Continue to page ${position + 2}"
                }
            }
        }
    }

    companion object {
        const val EXTRA_REPLAY = "io.github.noisystyle.nxtcam.extra.REPLAY_ONBOARDING"
    }
}
