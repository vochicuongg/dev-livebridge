package com.kakao.taxi.liveupdate

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            }
        }

        // --- Dark conversation card (icon + name + latest message) ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 18, 24))
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
            background = GradientDrawable().apply {
                setColor(accentColor)
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

        setContentView(unit)
        setFinishOnTouchOutside(true)

        // Anchor the unit just below the status/info bar with a small
        // breathing gap, where the expanded Now Bar card is displayed.
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: dp(24)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            attributes = attributes.apply {
                gravity = Gravity.TOP
                // Just under the status/info bar, with a 4dp gap so the
                // unit does not sit on top of it.
                y = statusBarHeight + dp(4)
                dimAmount = 0.2f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        }

        input.requestFocus()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
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
