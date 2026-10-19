package com.kakao.taxi.liveupdate

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
 *   Bar): this activity shows a blue reply bar anchored to the top of the
 *   screen, right where the expanded Now Bar card sits, mimicking the
 *   system's own inline reply frame (input field + Send). The typed text
 *   is handed to ReplyProxyService exactly like a host-provided
 *   RemoteInput result, so the rest of the reply pipeline is unchanged.
 */
class ReplyInputActivity : Activity() {

    private lateinit var input: EditText
    private var resultKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val vi = NativeAppStrings.language(this) == "vi"
        val titleText = conversation ?: label ?: if (vi) "Trả lời" else "Reply"
        val hintText = hint ?: if (vi) "Trả lời" else "Reply"
        val sendText = if (vi) "Gửi" else "Send"

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // Samsung-style reply bar: solid One UI blue, white text.
        val barBlue = Color.rgb(10, 108, 255)
        val fieldBlue = Color.argb(46, 255, 255, 255)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(barBlue)
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(8).toFloat()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            }
        }

        val title = TextView(this).apply {
            text = titleText
            textSize = 13f
            setTextColor(Color.WHITE)
            alpha = 0.85f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        card.addView(title)

        // The message being replied to, kept visible while typing (the
        // system collapses the Now Bar card when this editor opens).
        if (contextText != null) {
            val context = TextView(this).apply {
                text = contextText
                textSize = 15f
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            card.addView(context)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        input = EditText(this).apply {
            this.hint = hintText
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(190, 255, 255, 255))
            minLines = 1
            maxLines = 4
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(fieldBlue)
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { rightMargin = dp(8) }
        }
        row.addView(input)

        val send = TextView(this).apply {
            text = sendText
            textSize = 16f
            setTextColor(Color.WHITE)
            alpha = 0.9f
            setPadding(dp(10), dp(8), dp(6), dp(8))
            setOnClickListener { submit() }
        }
        row.addView(send)
        card.addView(row)

        setContentView(card)
        setFinishOnTouchOutside(true)

        // Anchor the window to the top edge, just below the status bar,
        // directly under where the expanded Now Bar card is displayed.
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
                y = statusBarHeight + dp(6)
                dimAmount = 0.2f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
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
