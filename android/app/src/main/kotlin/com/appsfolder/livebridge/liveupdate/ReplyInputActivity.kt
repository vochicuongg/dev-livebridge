package com.kakao.taxi.liveupdate

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
 *   Bar): this activity shows a small editor; the typed text is handed to
 *   ReplyProxyService exactly like a host-provided RemoteInput result, so
 *   the rest of the reply pipeline is unchanged.
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

        val vi = NativeAppStrings.language(this) == "vi"
        val titleText = label ?: if (vi) "Trả lời" else "Reply"
        val hintText = hint ?: if (vi) "Nhập tin nhắn" else "Type a message"
        val sendText = if (vi) "Gửi" else "Send"
        val cancelText = if (vi) "Hủy" else "Cancel"

        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = titleText
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = padding / 2 }
        }
        root.addView(title)

        input = EditText(this).apply {
            this.hint = hintText
            minLines = 1
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = padding / 2 }
        }
        root.addView(input)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelButton = Button(this).apply {
            text = cancelText
            setOnClickListener { finish() }
        }
        val sendButton = Button(this).apply {
            text = sendText
            setOnClickListener { submit() }
        }
        buttons.addView(cancelButton)
        buttons.addView(sendButton)
        root.addView(buttons)

        setContentView(root)

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
