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
import android.view.animation.DecelerateInterpolator
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

        val label = intent.getStringExtra(ReplyProxyService.EXTRA_REPLY_LABEL)
            ?.takeIf { it.isNotBlank() }
        val hint = intent.getStringExtra(ReplyProxyService.EXTRA_REPLY_HINT)
            ?.takeIf { it.isNotBlank() }
        val conversation = intent.getStringExtra(ReplyProxyService.EXTRA_REPLY_CONVERSATION)
            ?.takeIf { it.isNotBlank() }
        val contextText = intent.getStringExtra(ReplyProxyService.EXTRA_REPLY_CONTEXT_TEXT)
            ?.takeIf { it.isNotBlank() }
        val sourcePackage = intent.getStringExtra(ReplyProxyService.EXTRA_REPLY_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() }

        val vi = NativeAppStrings.language(this) == "vi"
        val titleText = conversation ?: label ?: if (vi) "Trả lời" else "Reply"
        val hintText = hint ?: if (vi) "Trả lời" else "Reply"
        val sendText = if (vi) "Gửi" else "Send"

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
        val accentColor = if (intent.hasExtra(ReplyProxyService.EXTRA_REPLY_ACCENT_COLOR)) {
            val provided = intent.getIntExtra(
                ReplyProxyService.EXTRA_REPLY_ACCENT_COLOR,
                Color.rgb(10, 108, 255)
            )
            Color.argb(255, Color.red(provided), Color.green(provided), Color.blue(provided))
        } else {
            Color.rgb(10, 108, 255)
        }


        val card = buildCardUnit(
            titleText = titleText,
            contextText = contextText,
            sourceIcon = sourceIcon,
            accentColor = accentColor,
            hintText = hintText,
            sendText = sendText,
            interactive = true
        )
        val unit = card.unit
        input = card.inputField!!
        currentUnit = unit
        attachSwipeGesture(card.header, unit)

        // Full-screen transparent root so the window's blur-behind covers
        // the whole backdrop (a window only as tall as the unit would hide
        // the blur behind the opaque card). The unit floats at the top;
        // tapping anywhere below it dismisses the editor.
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: dp(24)
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
                    // Just under the status/info bar, with a 4dp gap.
                    topMargin = statusBarHeight + dp(4)
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
        val preview = buildNextPreview()
        if (preview != null) {
            root.addView(
                preview,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply {
                    topMargin = statusBarHeight + dp(4)
                }
            )
            nextPreview = preview
        }

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
        }

        input.requestFocus()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
            // Gradient like the expanded Now Bar content surface, mixed
            // 50-50: the accent tint and the near-black end color meet at
            // an exact halfway blend in the middle of the card.
            val headerStart = blendColor(accentColor, Color.rgb(13, 18, 24), 0.5f)
            val headerEnd = Color.rgb(8, 11, 15)
            background = GradientDrawable(
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

        if (sourceIcon != null) {
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    rightMargin = dp(12)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            iconView.setImageDrawable(sourceIcon)
            header.addView(iconView)
        }

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
        if (contextText != null) {
            val message = TextView(this).apply {
                text = contextText
                textSize = 15f
                setTextColor(Color.WHITE)
                alpha = 0.92f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            headerTexts.addView(message)
        }
        header.addView(headerTexts)
        unit.addView(header)

        // --- Blue reply strip (input field + Send), attached below ---
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            val stripEnd = darkenColor(accentColor, 0.86f)
            background = GradientDrawable(
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
        return CardUnit(unit, header, if (interactive) field else null)
    }

    /**
     * Builds the passive preview card for the NEXT reply session (the
     * one a pull-down would switch to), pre-stacked behind the current
     * card: fully transparent and slightly scaled down at rest, rising
     * to full size/opacity as the pull progresses. Null when there is
     * no next conversation.
     */
    private fun buildNextPreview(): LinearLayout? {
        val keys = LiveUpdateNotifier.replyableMirrorKeys()
        val currentKey = intent.getStringExtra(ReplyProxyService.EXTRA_MIRROR_KEY).orEmpty()
        val index = keys.indexOf(currentKey)
        if (index < 0 || keys.size < 2) return null
        // The stack loops like the Now Bar: after the last card comes
        // the first one again.
        val nextIntent = LiveUpdateNotifier.replyEditorIntent(
            this,
            keys[(index + 1) % keys.size]
        ) ?: return null
        val conversation = nextIntent.getStringExtra(ReplyProxyService.EXTRA_REPLY_CONVERSATION)
            ?.takeIf { it.isNotBlank() }
        val label = nextIntent.getStringExtra(ReplyProxyService.EXTRA_REPLY_LABEL)
            ?.takeIf { it.isNotBlank() }
        val hint = nextIntent.getStringExtra(ReplyProxyService.EXTRA_REPLY_HINT)
            ?.takeIf { it.isNotBlank() }
        val contextText = nextIntent.getStringExtra(ReplyProxyService.EXTRA_REPLY_CONTEXT_TEXT)
            ?.takeIf { it.isNotBlank() }
        val sourcePackage = nextIntent.getStringExtra(ReplyProxyService.EXTRA_REPLY_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() }
        val sourceIcon = sourcePackage?.let { pkg ->
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (error: PackageManager.NameNotFoundException) {
                null
            }
        }
        val accentColor = if (nextIntent.hasExtra(ReplyProxyService.EXTRA_REPLY_ACCENT_COLOR)) {
            val provided = nextIntent.getIntExtra(
                ReplyProxyService.EXTRA_REPLY_ACCENT_COLOR,
                Color.rgb(10, 108, 255)
            )
            Color.argb(255, Color.red(provided), Color.green(provided), Color.blue(provided))
        } else {
            Color.rgb(10, 108, 255)
        }
        val vi = NativeAppStrings.language(this) == "vi"
        val card = buildCardUnit(
            titleText = conversation ?: label ?: if (vi) "Trả lời" else "Reply",
            contextText = contextText,
            sourceIcon = sourceIcon,
            accentColor = accentColor,
            hintText = hint ?: if (vi) "Trả lời" else "Reply",
            sendText = if (vi) "Gửi" else "Send",
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
                unit.scaleX = 1f - 0.04f * progress
                unit.scaleY = 1f - 0.04f * progress
                nextPreview?.let { preview ->
                    preview.alpha = progress
                    val scale = 0.94f + 0.06f * progress
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
                    // the pull, never below zero.
                    rawPull = (rawPull - distanceY).coerceAtLeast(0f)
                    val cap = unit.height * 0.6f
                    dampedPull = if (rawPull <= cap) {
                        rawPull * 0.55f
                    } else {
                        cap * 0.55f + (rawPull - cap) * 0.18f
                    }
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
     * Glides the current card down and out, then rebuilds the editor
     * for the NEXT reply session in
     * [LiveUpdateNotifier.replyableMirrorKeys] order, wrapping from
     * the last card back to the first like the Now Bar card stack.
     */
    private fun switchToNext(unit: LinearLayout) {
        if (switchingSession) return
        stopFollow()
        val keys = LiveUpdateNotifier.replyableMirrorKeys()
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
        // card POPS UP and slips BEHIND the next card at FULL size —
        // no shrinking: its Z drops below the next card's at once, then
        // it springs upward, so the front card covers its lower part
        // and it reads as filed behind the pile while the next card
        // takes the front. Then the editor rebuilds onto the next
        // conversation, seamlessly, because the preview already holds
        // the front pose.
        val hadPreview = nextPreview != null
        nextPreview?.let { preview ->
            preview.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        val oldHeight = unit.height
        val rebuild = {
            setIntent(targetIntent)
            onLaunch()
            val newUnit = currentUnit
            if (newUnit == null) {
                switchingSession = false
            } else if (hadPreview) {
                // The preview already showed this card in place at full
                // size: the rebuilt editor simply takes over.
                newUnit.translationY = 0f
                newUnit.alpha = 1f
                switchingSession = false
            } else {
                val travel = oldHeight + 40f * resources.displayMetrics.density
                newUnit.translationY = -travel * 0.6f
                newUnit.alpha = 0f
                newUnit.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .withEndAction { switchingSession = false }
                    .start()
            }
        }
        if (hadPreview) {
            // Behind from the very first frame of the release, then a
            // clean upward pop at full size — the Now Bar stack move.
            unit.translationZ = -12f * resources.displayMetrics.density
            unit.animate()
                .translationY(-unit.height * 0.5f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rebuild() }
                .start()
            // The fade runs on its own animator (a second animate()
            // call would reconfigure the same animator, not run in
            // parallel): the card fades once it is mostly behind.
            android.animation.ObjectAnimator.ofFloat(unit, "alpha", unit.alpha, 0f)
                .apply {
                    startDelay = 100
                    duration = 140
                    start()
                }
        } else {
            unit.animate()
                .translationY(-unit.height * 0.6f)
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rebuild() }
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
