package com.kakao.taxi.liveupdate

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
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

        input = EditText(this).apply {
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
        strip.addView(input)

        val send = TextView(this).apply {
            text = sendText
            textSize = 16f
            setTextColor(Color.WHITE)
            alpha = 0.95f
            setPadding(dp(10), dp(8), dp(8), dp(8))
            setOnClickListener { submit() }
        }
        strip.addView(send)
        unit.addView(strip)
        currentUnit = unit
        attachSwipeGesture(header, unit)

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

    /**
     * Swipe-to-switch between replyable Now Bar cards, mirroring the
     * system's card-stack gesture: pull the card DOWN (or push it UP)
     * and it follows the finger; release past a quarter of its height
     * (or fling) and it glides out while the next/previous
     * conversation glides in. Attached to the header card so it never
     * fights the text field.
     */
    private fun attachSwipeGesture(gestureView: LinearLayout, unit: LinearLayout) {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (switchingSession || unit.height == 0) return false
                    val startX = e1?.x ?: e2.x
                    val startY = e1?.y ?: e2.y
                    if (Math.abs(e2.y - startY) > Math.abs(e2.x - startX)) {
                        unit.translationY = (unit.translationY - distanceY)
                            .coerceIn(-unit.height.toFloat(), unit.height.toFloat())
                    }
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (Math.abs(velocityY) > Math.abs(velocityX) &&
                        Math.abs(velocityY) > 400f
                    ) {
                        // Flick down = next card, flick up = previous.
                        switchSession(if (velocityY > 0) 1 else -1, unit)
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
                    val threshold = unit.height * 0.25f
                    when {
                        unit.translationY >= threshold -> switchSession(1, unit)
                        unit.translationY <= -threshold -> switchSession(-1, unit)
                        else -> snapBack(unit)
                    }
                }
            }
            handled
        }
    }

    /** Animates back to rest when a swipe does not commit to a switch. */
    private fun snapBack(unit: LinearLayout) {
        if (unit.translationY != 0f) {
            unit.animate().translationY(0f).alpha(1f).setDuration(150).start()
        }
    }

    /**
     * Glides the current card out and rebuilds the editor for the
     * neighbouring reply session ([direction] +1 = next, -1 = previous)
     * in [LiveUpdateNotifier.replyableMirrorKeys] order. The card exits
     * in the swipe direction and the new one enters from the other side,
     * like the Now Bar card stack.
     */
    private fun switchSession(direction: Int, unit: LinearLayout) {
        if (switchingSession) return
        val keys = LiveUpdateNotifier.replyableMirrorKeys()
        val currentKey = intent.getStringExtra(ReplyProxyService.EXTRA_MIRROR_KEY).orEmpty()
        val index = keys.indexOf(currentKey)
        val targetIndex = index + direction
        if (index < 0 || keys.size < 2 || targetIndex !in keys.indices) {
            snapBack(unit)
            return
        }
        val targetIntent = LiveUpdateNotifier.replyEditorIntent(this, keys[targetIndex])
        if (targetIntent == null) {
            snapBack(unit)
            return
        }
        switchingSession = true
        val travel = unit.height + 40f * resources.displayMetrics.density
        val outY = if (direction > 0) travel else -travel
        unit.animate()
            .translationY(outY)
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                setIntent(targetIntent)
                onLaunch()
                val newUnit = currentUnit
                if (newUnit == null) {
                    switchingSession = false
                    return@withEndAction
                }
                newUnit.translationY = -outY * 0.6f
                newUnit.alpha = 0f
                newUnit.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .withEndAction { switchingSession = false }
                    .start()
            }
            .start()
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
