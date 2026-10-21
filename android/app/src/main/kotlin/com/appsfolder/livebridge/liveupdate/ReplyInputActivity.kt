package com.kakao.taxi.liveupdate

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Entry point for mirrored notifications' reply actions. The reply
 * PendingIntent targets this activity directly (notification-fired activity
 * intents are exempt from background-launch restrictions). Two cases:
 *
 * - The host surface provided its own RemoteInput editor (Wear OS, the
 *   notification shade): the launch intent already carries the reply text,
 *   which is forwarded straight to ReplyProxyService and the activity
 *   finishes without UI.
 * - The host fired the action with no editor (notably the Samsung Now
 *   Bar): this activity shows a combined unit at the top of the screen —
 *   a dark card with the conversation (app icon, name, latest message)
 *   and, attached directly below it, a blue reply strip with the input
 *   field and Send — mirroring how the Now Bar card and the system's
 *   inline reply frame look together. The typed text is handed to
 *   ReplyProxyService exactly like a host-provided RemoteInput result,
 *   so the rest of the reply pipeline is unchanged.
 */
class ReplyInputActivity : Activity() {

    private lateinit var input: EditText
    private var resultKey: String = ""
    private var currentUnit: LinearLayout? = null
    private var switchingSession = false
    private var nextPreview: LinearLayout? = null
    private var currentCard: CardUnit? = null
    private var rootView: FrameLayout? = null
    private var cardTopMargin: Int = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSwitchCompletion: Runnable? = null
    private var switchFadeAnimator: android.animation.Animator? = null

    /**
     * The editor only lives while its Now Bar mirror does: this watch
     * polls the CURRENT session's mirror (it follows card switches,
     * since it re-reads the intent every tick) and finishes the editor
     * as soon as the mirror notification is gone — read elsewhere,
     * swiped away or withdrawn by the source app.
     */
    private var mirrorMissCount = 0

    private val mirrorWatchRunnable = object : Runnable {
        override fun run() {
            val key = intent.getStringExtra(ReplyProxyService.EXTRA_MIRROR_KEY).orEmpty()
            if (key.isNotBlank() &&
                !LiveUpdateNotifier.isMirrorPosted(this@ReplyInputActivity, key)
            ) {
                // Two consecutive misses (~1.2 s) before closing, so a
                // momentary re-post gap never kills the editor.
                mirrorMissCount++
                if (mirrorMissCount >= 2) {
                    finish()
                    return
                }
            } else {
                mirrorMissCount = 0
            }
            mainHandler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: tapping Reply on another notification while this
        // editor is already open delivers the new intent here instead of
        // creating a second instance. Rebuild the editor for the new
        // conversation instead of silently swallowing the tap.
        setIntent(intent)
        onLaunch()
    }

    private fun onLaunch() {
        resultKey = intent.getStringExtra(ReplyProxyService.EXTRA_RESULT_KEY).orEmpty()
        nextPreview = null
        // Self-heal: every (re)launch starts from a clean slate, so a
        // switch left half-finished by rapid pull-release cycles can
        // never wedge the editor in an invisible, non-interactive state.
        switchingSession = false
        stopFollow()
        followTargetY = 0f
        pendingSwitchCompletion?.let { mainHandler.removeCallbacks(it) }
        pendingSwitchCompletion = null
        switchFadeAnimator?.cancel()
        switchFadeAnimator = null
        mainHandler.removeCallbacks(mirrorWatchRunnable)
        mirrorMissCount = 0

        // The host surface provided its own reply editor (Wear OS, the
        // notification shade): forward its RemoteInput results to the reply
        // pipeline immediately, without showing this editor.
        val hostText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(resultKey)
            ?.toString()
            ?.trim()
        if (!hostText.isNullOrBlank()) {
            forwardIntentToService()
            finish()
            return
        }

        val display = sessionDisplay(intent)

        val card = buildCardUnit(
            titleText = display.titleText,
            contextText = display.contextText,
            sourceIcon = display.sourceIcon,
            accentColor = display.accentColor,
            hintText = display.hintText,
            sendText = display.sendText,
            interactive = true
        )
        val unit = card.unit
        input = card.inputField!!
        currentUnit = unit
        currentCard = card
        attachSwipeGesture(card.header, unit)

        // Full-screen transparent root so the window's blur-behind covers
        // the whole backdrop (a window only as tall as the unit would hide
        // the blur behind the opaque card). The unit floats at the top;
        // tapping anywhere below it dismisses the editor.
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: dp(24)
        // On the Z Flip cover screen there is no status bar to clear:
        // the unit anchors to the very top of the cover display.
        val onCoverScreen = isOnCoverScreen()
        val unitTopMargin = if (onCoverScreen) dp(2) else statusBarHeight + dp(4)
        android.util.Log.d(
            "ReplyCover",
            "cover=$onCoverScreen dm=${resources.displayMetrics.widthPixels}x" +
                "${resources.displayMetrics.heightPixels} sbH=$statusBarHeight " +
                "topMargin=$unitTopMargin"
        )
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                unit,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply {
                    // Just under the status/info bar, with a 4dp gap —
                    // or flush to the top on the cover screen.
                    topMargin = unitTopMargin
                }
            )
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && event.y > unit.bottom) {
                    finish()
                    true
                } else {
                    false
                }
            }
        }
        setContentView(root)
        setFinishOnTouchOutside(true)

        // The NEXT replyable conversation's card, stacked behind the
        // current one (invisible at rest): the pull-down drag reveals it
        // as it scales up to take the front, like the Now Bar card stack.
        rootView = root
        cardTopMargin = unitTopMargin
        refreshNextPreview()

        window?.apply {
            // A faint dark veil over the blurred backdrop (frosted glass).
            setBackgroundDrawable(ColorDrawable(Color.argb(36, 8, 10, 14)))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            attributes = attributes.apply {
                gravity = Gravity.TOP
                y = 0
                dimAmount = 0.15f
                // Frosted-glass backdrop like the Now Bar reading view.
                blurBehindRadius = dp(40)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            if (onCoverScreen &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            ) {
                // Claim the whole cover window, including the top
                // camera-tab strip, so no system area pushes the
                // unit down on the cover screen.
                setDecorFitsSystemWindows(false)
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }

        if (onCoverScreen) {
            // Self-correct the top anchor once laid out, in two parts:
            // any offset INSIDE the window is subtracted from the top
            // margins, and if the window itself starts below the top
            // of the cover display, the window is pulled up by its own
            // offset. Repeated a few times because the cover window
            // manager may settle the window after the first layout.
            var windowPulledUp = false
            val correctCoverTop = {
                val decor = window?.decorView
                if (decor != null) {
                    val decorLoc = IntArray(2)
                    decor.getLocationOnScreen(decorLoc)
                    val unitLoc = IntArray(2)
                    unit.getLocationOnScreen(unitLoc)
                    val insideDelta = (unitLoc[1] - decorLoc[1]) - dp(2)
                    android.util.Log.d(
                        "ReplyCover",
                        "decorY=${decorLoc[1]} unitY=${unitLoc[1]} " +
                            "insideDelta=$insideDelta pulledUp=$windowPulledUp"
                    )
                    if (insideDelta > 0) {
                        val lp = unit.layoutParams as FrameLayout.LayoutParams
                        lp.topMargin -= insideDelta
                        unit.layoutParams = lp
                        cardTopMargin -= insideDelta
                        refreshNextPreview()
                    }
                    if (decorLoc[1] > 0 && !windowPulledUp) {
                        windowPulledUp = true
                        window?.let { w ->
                            w.attributes = w.attributes.apply { y -= decorLoc[1] }
                        }
                    }
                }
            }
            root.post { correctCoverTop() }
            mainHandler.postDelayed({ correctCoverTop() }, 300)
            mainHandler.postDelayed({ correctCoverTop() }, 800)
        }

        input.requestFocus()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        // Start the mirror watch (first tick immediately, so a stale
        // open onto an already-gone notification closes at once).
        mainHandler.post(mirrorWatchRunnable)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(mirrorWatchRunnable)
        pendingSwitchCompletion?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Whether this editor is running on the Z Flip cover screen: its
     * window is short and near-square (720x748 px on the Flip5), unlike
     * the tall main display.
     */
    private fun isOnCoverScreen(): Boolean {
        // The cover display is short and near-square (720x748 px on
        // the Flip5); the tall main display never matches either test.
        val metrics = resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        if (longSide in 1..1100 && longSide - shortSide <= 160) return true
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return false
        }
        return try {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.height() <= 1100 && bounds.width() <= 900
        } catch (e: Exception) {
            false
        }
    }

    /** Blends two colors; ratio 0 returns [from], 1 returns [to]. */
    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * r).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * r).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r).toInt()
        )
    }

    /** Scales a color's RGB channels by [factor] (below 1 darkens it). */
    private fun darkenColor(color: Int, factor: Float): Int {
        return Color.argb(
            255,
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    /** The built card unit plus the pieces callers need from it. */
    private data class CardUnit(
        val unit: LinearLayout,
        val header: LinearLayout,
        val strip: LinearLayout,
        val iconView: ImageView,
        val titleView: TextView,
        val messageView: TextView,
        val inputField: EditText?
    )

    /**
     * Builds the combined reply card (conversation header + reply
     * strip) from plain display data, so the same construction renders
     * both the live editor ([interactive] = true) and the passive
     * next-card preview stacked behind it during the pull-down gesture.
     */
    private fun buildCardUnit(
        titleText: String,
        contextText: String?,
        sourceIcon: android.graphics.drawable.Drawable?,
        accentColor: Int,
        hintText: String,
        sendText: String,
        interactive: Boolean
    ): CardUnit {
        val radius = dp(24).toFloat()

        // One combined unit: dark conversation card on top, blue reply
        // strip attached below, sharing the same width and corner radius.
        val unit = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(8).toFloat()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Inset from both screen edges like the expanded Now Bar
            // card. Padding, not margins: margins on an activity's root
            // view are silently dropped by the window, which is why the
            // earlier margin attempts never narrowed the unit.
            setPadding(dp(16), 0, dp(16), 0)
        }

        // --- Dark conversation card (icon + name + latest message) ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = headerBackground(accentColor, radius)
        }

        // Always created (hidden when the app has no icon) so a session
        // switch can rebind it in place.
        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                rightMargin = dp(12)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (sourceIcon != null) {
                setImageDrawable(sourceIcon)
            } else {
                visibility = View.GONE
            }
        }
        header.addView(iconView)

        val headerTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val title = TextView(this).apply {
            text = titleText
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerTexts.addView(title)
        // Always created (hidden when there is no message to show) so a
        // session switch can rebind it in place.
        val message = TextView(this).apply {
            text = contextText ?: ""
            textSize = 15f
            setTextColor(Color.WHITE)
            alpha = 0.92f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            visibility = if (contextText != null) View.VISIBLE else View.GONE
        }
        headerTexts.addView(message)
        header.addView(headerTexts)
        unit.addView(header)

        // --- Blue reply strip (input field + Send), attached below ---
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = stripBackground(accentColor, radius)
        }

        val field = EditText(this).apply {
            this.hint = hintText
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(190, 255, 255, 255))
            minLines = 1
            maxLines = 4
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.argb(46, 255, 255, 255))
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { rightMargin = dp(6) }
        }
        strip.addView(field)

        val send = TextView(this).apply {
            text = sendText
            textSize = 16f
            setTextColor(Color.WHITE)
            alpha = 0.95f
            setPadding(dp(10), dp(8), dp(8), dp(8))
            if (interactive) {
                setOnClickListener { submit() }
            }
        }
        strip.addView(send)
        unit.addView(strip)
        if (!interactive) {
            field.isEnabled = false
            field.isFocusable = false
        }
        return CardUnit(unit, header, strip, iconView, title, message, if (interactive) field else null)
    }

    /** Display data for one reply session, parsed from its intent extras. */
    private data class SessionDisplay(
        val titleText: String,
        val contextText: String?,
        val sourceIcon: android.graphics.drawable.Drawable?,
        val accentColor: Int,
        val hintText: String,
        val sendText: String
    )

    private fun sessionDisplay(source: Intent): SessionDisplay {
        val label = source.getStringExtra(ReplyProxyService.EXTRA_REPLY_LABEL)
            ?.takeIf { it.isNotBlank() }
        val hint = source.getStringExtra(ReplyProxyService.EXTRA_REPLY_HINT)
            ?.takeIf { it.isNotBlank() }
        val conversation = source.getStringExtra(ReplyProxyService.EXTRA_REPLY_CONVERSATION)
            ?.takeIf { it.isNotBlank() }
        val contextText = source.getStringExtra(ReplyProxyService.EXTRA_REPLY_CONTEXT_TEXT)
            ?.takeIf { it.isNotBlank() }
        val sourcePackage = source.getStringExtra(ReplyProxyService.EXTRA_REPLY_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() }
        val vi = NativeAppStrings.language(this) == "vi"
        val sourceIcon = sourcePackage?.let { pkg ->
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (error: PackageManager.NameNotFoundException) {
                null
            }
        }
        // The strip wears the same accent color as this app's collapsed
        // Now Bar pill (computed by the notifier with the same precedence
        // as the mirror itself). Falls back to Samsung blue when the
        // notifier did not provide one.
        val accentColor = if (source.hasExtra(ReplyProxyService.EXTRA_REPLY_ACCENT_COLOR)) {
            val provided = source.getIntExtra(
                ReplyProxyService.EXTRA_REPLY_ACCENT_COLOR,
                Color.rgb(10, 108, 255)
            )
            Color.argb(255, Color.red(provided), Color.green(provided), Color.blue(provided))
        } else {
            Color.rgb(10, 108, 255)
        }
        return SessionDisplay(
            titleText = conversation ?: label ?: if (vi) "Trả lời" else "Reply",
            contextText = contextText,
            sourceIcon = sourceIcon,
            accentColor = accentColor,
            hintText = hint ?: if (vi) "Trả lời" else "Reply",
            sendText = if (vi) "Gửi" else "Send"
        )
    }

    /**
     * The header card's gradient, like the expanded Now Bar content
     * surface, mixed 50-50: the accent tint and the near-black end
     * color meet at an exact halfway blend in the middle of the card.
     */
    private fun headerBackground(accentColor: Int, radius: Float): GradientDrawable {
        val headerStart = blendColor(accentColor, Color.rgb(13, 18, 24), 0.5f)
        val headerEnd = Color.rgb(8, 11, 15)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                headerStart,
                blendColor(headerStart, headerEnd, 0.5f),
                headerEnd
            )
        ).apply {
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius, // top-left, top-right
                0f, 0f, 0f, 0f // bottom corners square: strip attaches here
            )
        }
    }

    /** The reply strip's vertical 50-50 gradient in the session accent. */
    private fun stripBackground(accentColor: Int, radius: Float): GradientDrawable {
        val stripEnd = darkenColor(accentColor, 0.86f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                accentColor,
                blendColor(accentColor, stripEnd, 0.5f),
                stripEnd
            )
        ).apply {
            cornerRadii = floatArrayOf(
                0f, 0f, 0f, 0f, // top corners square: attaches to the card
                radius, radius, radius, radius // bottom-left, bottom-right
            )
        }
    }

    /** Rebinds the live card's views to another session, in place. */
    private fun bindSession(display: SessionDisplay) {
        val card = currentCard ?: return
        val radius = dp(24).toFloat()
        card.titleView.text = display.titleText
        card.messageView.text = display.contextText ?: ""
        card.messageView.visibility =
            if (display.contextText != null) View.VISIBLE else View.GONE
        if (display.sourceIcon != null) {
            card.iconView.setImageDrawable(display.sourceIcon)
            card.iconView.visibility = View.VISIBLE
        } else {
            card.iconView.setImageDrawable(null)
            card.iconView.visibility = View.GONE
        }
        card.header.background = headerBackground(display.accentColor, radius)
        card.strip.background = stripBackground(display.accentColor, radius)
        card.inputField?.hint = display.hintText
        card.inputField?.text?.clear()
    }

    /** (Re)builds the next-card preview stacked behind the live card. */
    private fun refreshNextPreview() {
        val root = rootView ?: return
        nextPreview?.let { preview ->
            (preview.parent as? ViewGroup)?.removeView(preview)
        }
        nextPreview = null
        val preview = buildNextPreview() ?: return
        root.addView(
            preview,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { topMargin = cardTopMargin }
        )
        nextPreview = preview
    }

    /**
     * Completes a switch IN PLACE: the same views stay on screen and
     * are rebound to the new session, so a gesture stream is never
     * orphaned. Runs when the release animation's timer fires — or
     * instantly when a fresh pull interrupts it, which keeps rapid
     * pull-release cycles fluid instead of locked out.
     */
    private fun completeSwitchInPlace(targetIntent: Intent) {
        pendingSwitchCompletion?.let { mainHandler.removeCallbacks(it) }
        pendingSwitchCompletion = null
        switchFadeAnimator?.cancel()
        switchFadeAnimator = null
        if (isFinishing) {
            switchingSession = false
            return
        }
        mirrorMissCount = 0
        setIntent(targetIntent)
        resultKey = targetIntent.getStringExtra(ReplyProxyService.EXTRA_RESULT_KEY).orEmpty()
        val unit = currentUnit ?: run {
            switchingSession = false
            return
        }
        unit.animate().cancel()
        unit.translationY = 0f
        unit.translationZ = 0f
        unit.alpha = 1f
        unit.scaleX = 1f
        unit.scaleY = 1f
        bindSession(sessionDisplay(targetIntent))
        refreshNextPreview()
        switchingSession = false
    }

    /**
     * Builds the passive preview card for the NEXT reply session (the
     * one a pull-down would switch to), pre-stacked behind the current
     * card: fully transparent and slightly scaled down at rest, rising
     * to full size/opacity as the pull progresses. Null when there is
     * no next conversation.
     */
    private fun buildNextPreview(): LinearLayout? {
        val keys = LiveUpdateNotifier.replyableMirrorKeys(this)
        val currentKey = intent.getStringExtra(ReplyProxyService.EXTRA_MIRROR_KEY).orEmpty()
        val index = keys.indexOf(currentKey)
        if (index < 0 || keys.size < 2) return null
        // The stack loops like the Now Bar: after the last card comes
        // the first one again.
        val nextIntent = LiveUpdateNotifier.replyEditorIntent(
            this,
            keys[(index + 1) % keys.size]
        ) ?: return null
        val display = sessionDisplay(nextIntent)
        val card = buildCardUnit(
            titleText = display.titleText,
            contextText = display.contextText,
            sourceIcon = display.sourceIcon,
            accentColor = display.accentColor,
            hintText = display.hintText,
            sendText = display.sendText,
            interactive = false
        )
        return card.unit.apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            pivotY = 0f
            elevation = 0f
        }
    }

    // --- Frame-driven drag follow ------------------------------------
    // A single Choreographer loop eases the current card (and the
    // stacked preview behind it) toward the drag target every frame.
    // This replaces per-touch-event property animators, whose constant
    // restarts fought each other and made the drag feel steppy.
    private var followTargetY = 0f
    private var followDragging = false
    private var followPosted = false

    private val followCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val unit = currentUnit
            if (unit == null) {
                followPosted = false
                return
            }
            val target = followTargetY
            val next = unit.translationY + (target - unit.translationY) * 0.35f
            unit.translationY = if (Math.abs(target - next) < 0.6f) target else next
            if (unit.height > 0) {
                val progress = (unit.translationY / (unit.height * 0.55f))
                    .coerceIn(0f, 1f)
                // The dragged card keeps its full size; the next card
                // behind it stays a touch smaller the whole drag, like
                // the Now Bar stack.
                nextPreview?.let { preview ->
                    preview.alpha = progress
                    val scale = 0.94f + 0.03f * progress
                    preview.scaleX = scale
                    preview.scaleY = scale
                }
            }
            if (followDragging || Math.abs(target - unit.translationY) >= 0.6f) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                followPosted = false
            }
        }
    }

    private fun startFollow() {
        if (!followPosted) {
            followPosted = true
            Choreographer.getInstance().postFrameCallback(followCallback)
        }
    }

    private fun stopFollow() {
        followDragging = false
        Choreographer.getInstance().removeFrameCallback(followCallback)
        followPosted = false
    }

    /**
     * Pull-down-to-next between replyable Now Bar cards, mirroring the
     * system's card-stack gesture: drag the card DOWN and it follows
     * the finger; release and it glides out while the NEXT conversation
     * glides in from the top. One-way only — there is no swipe back.
     * Attached to the header card so it never fights the text field.
     *
     * The drag feel copies the system's card: the raw pull is
     * rubber-band damped (the card travels only part of the finger's
     * distance and resists harder the further it goes) and the card
     * eases toward the damped target with short trailing animations
     * instead of tracking the finger 1:1 — so small finger tremors do
     * not make it jitter.
     */
    private fun attachSwipeGesture(gestureView: LinearLayout, unit: LinearLayout) {
        var rawPull = 0f
        var dampedPull = 0f
        var axisLocked = false
        var axisVertical = false
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    // A switch still animating? Commit it instantly —
                    // the swap is in place on these very views, so this
                    // gesture continues seamlessly on the new card.
                    // No lockout: rapid pull-release cycles stay fluid.
                    pendingSwitchCompletion?.let { completion ->
                        mainHandler.removeCallbacks(completion)
                        completion.run()
                    }
                    rawPull = 0f
                    dampedPull = 0f
                    axisLocked = false
                    axisVertical = false
                    unit.animate().cancel()
                    followTargetY = 0f
                    followDragging = true
                    startFollow()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (switchingSession || unit.height == 0) return false
                    if (!axisLocked && e1 != null) {
                        val slop = 8f * resources.displayMetrics.density
                        val cumDx = Math.abs(e2.x - e1.x)
                        val cumDy = Math.abs(e2.y - e1.y)
                        if (cumDx > slop || cumDy > slop) {
                            // Commit to one axis for the whole gesture so
                            // diagonal wobble cannot start/stop the drag.
                            axisLocked = true
                            axisVertical = cumDy > cumDx
                        }
                    }
                    if (!axisVertical) return true
                    // Downward only: upward finger movement just unwinds
                    // the pull, never below zero. The travel range is
                    // capped like the Now Bar card's: the card can be
                    // pulled at most ~55% of its own height, with the
                    // rubber band stiffening towards that limit.
                    rawPull = (rawPull - distanceY).coerceAtLeast(0f)
                    val cap = unit.height * 0.6f
                    val damped = if (rawPull <= cap) {
                        rawPull * 0.55f
                    } else {
                        cap * 0.55f + (rawPull - cap) * 0.10f
                    }
                    dampedPull = damped.coerceAtMost(unit.height * 0.55f)
                    // The frame loop eases the card (and the stacked
                    // preview behind it) toward this target.
                    followTargetY = dampedPull
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (velocityY > 400f && velocityY > Math.abs(velocityX)) {
                        switchToNext(unit)
                        return true
                    }
                    return false
                }
            }
        )
        gestureView.setOnTouchListener { _, event ->
            val handled = detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                if (!switchingSession && unit.height > 0) {
                    // A deliberate pull then release switches to the next
                    // conversation; anything less settles back smoothly.
                    val threshold = 28f * resources.displayMetrics.density
                    if (dampedPull >= threshold) {
                        switchToNext(unit)
                    } else {
                        snapBack(unit)
                    }
                }
            }
            handled
        }
    }

    /** Animates back to rest when a swipe does not commit to a switch. */
    private fun snapBack(unit: LinearLayout) {
        stopFollow()
        nextPreview?.let { preview ->
            preview.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        if (unit.translationY != 0f || unit.scaleX != 1f) {
            unit.animate()
                .translationY(0f)
                .translationZ(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Glides the current card down and out, then switches the editor
     * for the NEXT reply session in
     * [LiveUpdateNotifier.replyableMirrorKeys] order, wrapping from
     * the last card back to the first like the Now Bar card stack.
     */
    private fun switchToNext(unit: LinearLayout) {
        if (switchingSession) return
        stopFollow()
        val keys = LiveUpdateNotifier.replyableMirrorKeys(this)
        val currentKey = intent.getStringExtra(ReplyProxyService.EXTRA_MIRROR_KEY).orEmpty()
        val index = keys.indexOf(currentKey)
        // Loop like the Now Bar stack: after the last card, the first.
        if (index < 0 || keys.size < 2) {
            snapBack(unit)
            return
        }
        val targetIndex = (index + 1) % keys.size
        val targetIntent = LiveUpdateNotifier.replyEditorIntent(this, keys[targetIndex])
        if (targetIntent == null) {
            snapBack(unit)
            return
        }
        switchingSession = true
        // Release effect, copied from the Now Bar stack: the pulled
        // card's Z drops below the next card's at once, then it POPS
        // UP past the slot and settles back into the EXACT original
        // position — full size, one layer behind — while the next
        // card takes the front. Then the editor switches onto the
        // next conversation in place, seamlessly, because the preview
        // already holds the front pose and the filed card has faded.
        val hadPreview = nextPreview != null
        nextPreview?.let { preview ->
            preview.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(380)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        // Completion is TIMER-driven, never hung off an animator's end
        // action: if anything cancels the visuals mid-flight, the
        // switch still completes and the editor can never wedge in a
        // half-switched, invisible, gesture-dead state.
        val completion = Runnable { completeSwitchInPlace(targetIntent) }
        pendingSwitchCompletion = completion
        if (hadPreview) {
            mainHandler.postDelayed(completion, 380)
            // Behind from the very first frame of the release; the
            // overshoot spring carries the card up past the slot and
            // settles it back into the exact original position, one
            // layer behind the new front card — the Now Bar stack move.
            unit.translationZ = -12f * resources.displayMetrics.density
            unit.animate()
                .translationY(0f)
                .setDuration(380)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
            // The fade runs on its own animator (a second animate()
            // call would reconfigure the same animator, not run in
            // parallel): it fades only while settling into the back
            // slot, hiding the switch seam.
            switchFadeAnimator =
                android.animation.ObjectAnimator.ofFloat(unit, "alpha", unit.alpha, 0f)
                    .apply {
                        startDelay = 250
                        duration = 130
                        start()
                    }
        } else {
            mainHandler.postDelayed(completion, 220)
            unit.animate()
                .translationY(-unit.height * 0.6f)
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Forwards this activity's launch intent (extras + RemoteInput results) to ReplyProxyService. */
    private fun forwardIntentToService() {
        val serviceIntent = Intent(this, ReplyProxyService::class.java).apply {
            action = ReplyProxyService.ACTION_PROXY_REPLY
            intent.extras?.let { putExtras(it) }
            clipData = intent.clipData
        }
        try {
            startService(serviceIntent)
        } catch (error: Exception) {
            // Nothing sensible to fall back to here; the editor path logs its own failures.
        }
    }

    private fun submit() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || resultKey.isBlank()) {
            if (text.isBlank()) {
                input.error = if (NativeAppStrings.language(this) == "vi") {
                    "Tin nhắn trống"
                } else {
                    "Message is empty"
                }
            }
            return
        }

        val serviceIntent = Intent(this, ReplyProxyService::class.java).apply {
            action = ReplyProxyService.ACTION_PROXY_REPLY
            intent.extras?.let { putExtras(it) }
        }
        val results = Bundle().apply {
            putCharSequence(resultKey, text)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(resultKey).build()),
            serviceIntent,
            results
        )
        try {
            startService(serviceIntent)
        } catch (error: Exception) {
            // Service could not start; keep the editor open so the text is not lost.
            return
        }
        finish()
    }
}
