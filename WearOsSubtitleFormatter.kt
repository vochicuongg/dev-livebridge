package com.kakao.taxi.liveupdate

import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Helper function to format name with app name as a subtitle.
 * 
 * This function takes the original name (e.g., sender name or conversation title)
 * and appends the app name on a new line with smaller font size and optional styling.
 * 
 * Example output:
 * ```
 * Cường
 * Zalo
 * ```
 * 
 * @param originalName The original name (sender name or conversation title)
 * @param appName The application name to display as subtitle
 * @param subtitleColor Optional color for the subtitle text (default: gray)
 * @param subtitleSizeRatio Size ratio for subtitle relative to original text (default: 0.75 = 75%)
 * @param useItalic Whether to italicize the subtitle (default: true)
 * @return A CharSequence with the formatted text including spannable styling
 */
fun formatNameWithAppSubtitle(
    originalName: CharSequence?,
    appName: String,
    subtitleColor: Int? = 0xFF888888.toInt(), // Light gray color
    subtitleSizeRatio: Float = 0.75f,
    useItalic: Boolean = true
): CharSequence {
    // If original name is null or empty, just return the app name
    if (originalName.isNullOrBlank()) {
        return appName
    }

    // Build the complete text with SpannableStringBuilder
    val builder = SpannableStringBuilder()
    
    // Add the original name (no styling changes)
    builder.append(originalName)
    
    // Add newline character to move app name to next line
    builder.append("\n")
    
    // Create a SpannableString for the app name with formatting
    val appNameSpannable = SpannableString(appName)
    
    // Apply relative size span to make the text smaller (75% of original size)
    appNameSpannable.setSpan(
        RelativeSizeSpan(subtitleSizeRatio),
        0,
        appName.length,
        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    
    // Apply color if provided
    if (subtitleColor != null) {
        appNameSpannable.setSpan(
            ForegroundColorSpan(subtitleColor),
            0,
            appName.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    
    // Apply italic style if requested
    if (useItalic) {
        appNameSpannable.setSpan(
            StyleSpan(Typeface.ITALIC),
            0,
            appName.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    
    // Append the styled app name to the builder
    builder.append(appNameSpannable)
    
    return builder
}

/**
 * Alternative version without italic styling for a cleaner look
 */
fun formatNameWithAppSubtitleSimple(
    originalName: CharSequence?,
    appName: String
): CharSequence {
    return formatNameWithAppSubtitle(
        originalName = originalName,
        appName = appName,
        subtitleColor = 0xFF999999.toInt(),
        subtitleSizeRatio = 0.75f,
        useItalic = false
    )
}
