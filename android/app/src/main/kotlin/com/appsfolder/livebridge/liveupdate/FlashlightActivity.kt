package com.kakao.taxi.liveupdate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Round 143: in-app flashlight brightness screen with a real drag
 * slider. A drag slider cannot live in the notification or the Now
 * Bar pill — Android delivers only clicks from RemoteViews, never
 * drag events — so it lives here: tapping the flashlight card opens
 * this screen (see FlashlightNotificationBuilder's content intent).
 *
 * The bar has one step per hardware brightness level
 * (FlashlightController.FLASHLIGHT_LEVEL_COUNT). Dragging sends the
 * level to FlashlightForegroundService exactly like tapping a segment
 * in the pill; the service applies it, persists it and refreshes the
 * notification, so the pill's dots follow the drag. Only index
 * changes are sent (a drag crosses at most 4 boundaries), never every
 * progress tick. The pill itself is untouched: same dots slider,
 * tap-to-set.
 */
class FlashlightActivity : Activity() {

    private val prefs by lazy { ConverterPrefs(this) }
    private val controller by lazy { FlashlightController(this) }

    private lateinit var seekBar: SeekBar
    private var lastSentLevelIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flashlight)

        val capability = controller.getCapability()
        if (!capability.available) {
            finish()
            return
        }

        findViewById<TextView>(R.id.flashlight_drag_title).text =
            FlashlightStrings.title(this)
        findViewById<TextView>(R.id.flashlight_drag_turn_off).apply {
            text = FlashlightStrings.turnOff(this@FlashlightActivity)
            setOnClickListener {
                sendDisable()
                finish()
            }
        }

        val initialLevel = prefs.getSmartFlashlightLevel()
            .coerceIn(0, FlashlightController.FLASHLIGHT_LEVEL_COUNT - 1)
        lastSentLevelIndex = initialLevel
        seekBar = findViewById<SeekBar>(R.id.flashlight_drag_seekbar).apply {
            max = FlashlightController.FLASHLIGHT_LEVEL_COUNT - 1
            progress = initialLevel
            // Same rule as the pill's slider: no 5-level support, no
            // level control — the bar is shown disabled instead.
            isEnabled = capability.supportsFiveLevels
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser || progress == lastSentLevelIndex) return
                    lastSentLevelIndex = progress
                    sendLevel(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
    }

    override fun onResume() {
        super.onResume()
        // The level may have moved while this screen was paused (shade,
        // Quick Panel): re-read it. Setting progress programmatically
        // fires onProgressChanged with fromUser=false, so nothing is
        // re-sent — and lastSentLevelIndex is re-synced with it.
        if (!prefs.getSmartFlashlightEnabled()) {
            finish()
            return
        }
        val currentLevel = prefs.getSmartFlashlightLevel()
            .coerceIn(0, FlashlightController.FLASHLIGHT_LEVEL_COUNT - 1)
        lastSentLevelIndex = currentLevel
        if (::seekBar.isInitialized && seekBar.progress != currentLevel) {
            seekBar.progress = currentLevel
        }
    }

    private fun sendLevel(levelIndex: Int) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, FlashlightForegroundService::class.java).apply {
                action = FlashlightForegroundService.ACTION_SET_LEVEL
                putExtra(FlashlightForegroundService.EXTRA_LEVEL_INDEX, levelIndex)
            }
        )
    }

    private fun sendDisable() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, FlashlightForegroundService::class.java).apply {
                action = FlashlightForegroundService.ACTION_DISABLE
            }
        )
    }
}
