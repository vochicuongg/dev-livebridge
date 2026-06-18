package com.kakao.taxi.liveupdate

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as RemoteInputCompat
import androidx.core.graphics.drawable.IconCompat
import com.kakao.taxi.R

/**
 * ============================================================
 * WEAR OS REFACTORED CODE - BIGTEXTSTYLE WITH REPLY ACTION
 * ============================================================
 * 
 * This refactored implementation uses BigTextStyle instead of MessagingStyle
 * to maintain full control over the notification appearance on Wear OS,
 * while preserving the Reply action (RemoteInput) for user interaction.
 * 
 * Key Features:
 * 1. Uses BigTextStyle to display message content
 * 2. Manually inserts app name in the title (not lost by OS)
 * 3. Extracts and preserves Reply action with RemoteInput
 * 4. Maintains avatar/large icon
 * 5. Handles both messaging and non-messaging notifications
 */

// ============================================================
// HELPER FUNCTION: Extract Reply Action with RemoteInput
// ============================================================

/**
 * Extracts the Reply action (containing RemoteInput) from the source notification.
 * This is essential for Wear OS to enable voice/keyboard input for replying to messages.
 * 
 * @param sbn Source StatusBarNotification from which to extract the Reply action
 * @return NotificationCompat.Action containing RemoteInput, or null if not found
 */
private fun extractReplyAction(sbn: StatusBarNotification): NotificationCompat.Action? {
    return try {
        val actions = sbn.notification.actions ?: return null
        
        // Find the first action that has RemoteInput (this is the Reply action)
        val replyAction = actions.firstOrNull { action ->
            action.remoteInputs != null && action.remoteInputs.isNotEmpty()
        } ?: return null
        
        // Convert framework Action to NotificationCompat.Action
        NotificationCompat.Action.Builder(
            replyAction.icon,
            replyAction.title,
            replyAction.actionIntent
        ).apply {
            // Copy RemoteInputs - this is the critical part for Wear OS Reply functionality
            replyAction.remoteInputs?.forEach { remoteInput ->
                addRemoteInput(RemoteInputCompat.fromPlatform(remoteInput))
            }
            
            // Copy extras if any
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                replyAction.extras?.let { addExtras(it) }
            }
            
            // Copy other properties
            setAllowGeneratedReplies(replyAction.allowGeneratedReplies)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setSemanticAction(replyAction.semanticAction)
            }
        }.build()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to extract Reply action with RemoteInput", e)
        null
    }
}

// ============================================================
// MAIN REFACTORED FUNCTION: applyWearOsSourcePresentation
// ============================================================

/**
 * Applies Wear OS-specific presentation using BigTextStyle instead of MessagingStyle.
 * 
 * This gives us full control over text formatting (inserting app names, styling, etc.)
 * while preserving the Reply action for Wear OS interaction.
 * 
 * @param context Application context
 * @param builder NotificationCompat.Builder to modify
 * @param sbn Source StatusBarNotification containing the original notification
 * @param sourceLargeIcon Optional large icon bitmap to display
 */
private fun applyWearOsSourcePresentation(
    context: Context,
    builder: NotificationCompat.Builder,
    sbn: StatusBarNotification,
    sourceLargeIcon: Bitmap?
) {
    val source = sbn.notification
    val sourcePackageName = sbn.packageName
    
    // ========================================
    // STEP 1: Get App Name and Avatar
    // ========================================
    
    // Get app name - priority order: SubText > WearOsAppHeader > "App"
    val resolvedSubText = resolveWearOsSubText(context, source, sourcePackageName)
    val appNameStr = resolvedSubText 
        ?: resolveWearOsAppHeader(context, sourcePackageName)?.appName 
        ?: "App"
    
    // Extract avatar bitmap from source
    val avatarBitmap = sourceLargeIcon 
        ?: resolveAppLargeIconBitmap(context, sourcePackageName)
    
    // ========================================
    // STEP 2: Process Content (Use BigTextStyle, NOT MessagingStyle)
    // ========================================
    
    try {
        // Check if the source notification is a messaging notification
        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(source)
        
        if (messagingStyle != null) {
            // --- CASE A: Messaging notification (Chat apps) ---
            
            // Get conversation title and user info
            val conversationTitle = messagingStyle.conversationTitle?.toString() ?: ""
            val userName = messagingStyle.user?.name?.toString() ?: ""
            
            // Build title with app name
            val titleText = if (conversationTitle.isNotEmpty()) {
                "[$appNameStr] $conversationTitle"
            } else if (userName.isNotEmpty()) {
                "[$appNameStr] $userName"
            } else {
                "[$appNameStr]"
            }
            builder.setContentTitle(titleText)
            
            // Build chat history using SpannableStringBuilder
            val chatHistory = SpannableStringBuilder()
            val messages = messagingStyle.messages
            
            // Limit messages to avoid OOM on watch (take last 10 messages)
            val recentMessages = if (messages.size > MAX_WEAR_MESSAGING_STYLE_MESSAGES) {
                messages.takeLast(MAX_WEAR_MESSAGING_STYLE_MESSAGES)
            } else {
                messages
            }
            
            // Build chat history: "Sender: Message\nSender2: Message2..."
            recentMessages.forEachIndexed { index, message ->
                if (index > 0) {
                    chatHistory.append("\n")
                }
                
                // Get sender name
                val senderName = message.person?.name?.toString()
                    ?: messagingStyle.user?.name?.toString()
                    ?: "Unknown"
                
                // Append in format: "Sender: Message"
                chatHistory.append(senderName)
                chatHistory.append(": ")
                chatHistory.append(message.text ?: "")
            }
            
            // Set BigTextStyle with chat history
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(chatHistory)
            )
            
        } else {
            // --- CASE B: Regular notification (Non-messaging) ---
            
            val originalTitle = source.extras?.getCharSequence(Notification.EXTRA_TITLE)
            val originalText = source.extras?.getCharSequence(Notification.EXTRA_TEXT)
            val bigText = source.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            
            // Build title with app name
            val titleText = if (!originalTitle.isNullOrBlank()) {
                "[$appNameStr] $originalTitle"
            } else {
                "[$appNameStr]"
            }
            builder.setContentTitle(titleText)
            
            // Use BigTextStyle to preserve full text content
            val contentText = bigText ?: originalText
            if (contentText != null) {
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(contentText)
                )
            }
        }
        
    } catch (e: Exception) {
        Log.w(TAG, "applyWearOsSourcePresentation: Failed to process content with BigTextStyle", e)
        
        // Fallback: Just use original text if extraction fails
        val originalTitle = source.extras?.getCharSequence(Notification.EXTRA_TITLE)
        val originalText = source.extras?.getCharSequence(Notification.EXTRA_TEXT)
        
        if (originalTitle != null) {
            builder.setContentTitle("[$appNameStr] $originalTitle")
        }
        if (originalText != null) {
            builder.setContentText(originalText.toString())
        }
    }
    
    // ========================================
    // STEP 3: Extract and Add Reply Action (CRITICAL!)
    // ========================================
    
    val replyAction = extractReplyAction(sbn)
    if (replyAction != null) {
        // Add Reply action to builder
        builder.addAction(replyAction)
        
        // Also add to WearableExtender for optimal Wear OS display
        try {
            val wearableExtender = NotificationCompat.WearableExtender()
            wearableExtender.addAction(replyAction)
            builder.extend(wearableExtender)
            
            Log.d(TAG, "Successfully added Reply action with RemoteInput for Wear OS")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add Reply action to WearableExtender", e)
        }
    } else {
        Log.d(TAG, "No Reply action found in source notification")
    }
    
    // ========================================
    // STEP 4: Preserve Avatar (Large Icon)
    // ========================================
    
    avatarBitmap?.let { bitmap ->
        builder.setLargeIcon(bitmap)
    }
    
    // ========================================
    // STEP 5: Preserve Small Icon and Color
    // ========================================
    
    // Rasterize small icon to avoid cross-package resource issues on Wear OS
    try {
        val originalSmallIcon = resolveSourceSmallIcon(context, sbn)
            ?: resolveAppSmallIcon(context, sourcePackageName)
        
        val rasterizedSmallIcon = rasterizeSmallIcon(
            context = context,
            icon = originalSmallIcon,
            fallbackIcon = IconCompat.createWithResource(context, R.drawable.ic_stat_liveupdate)
        )
        
        rasterizedSmallIcon?.let { builder.setSmallIcon(it) }
        
        // Preserve original notification color tint
        val originalColor = source.color
        if (originalColor != 0) {
            builder.setColor(originalColor)
        }
        
    } catch (e: Exception) {
        Log.w(TAG, "Failed to set small icon for Wear OS", e)
    }
    
    // ========================================
    // STEP 6: Set SubText (Optional)
    // ========================================
    
    resolvedSubText?.let { builder.setSubText(it) }
}

// ============================================================
// HELPER FUNCTIONS (Assumed to exist in LiveUpdateNotifier.kt)
// ============================================================

/**
 * Maximum number of messages to display in Wear OS chat history.
 * Prevents OOM errors on watches with limited memory.
 */
private const val MAX_WEAR_MESSAGING_STYLE_MESSAGES = 10

/**
 * Resolves the SubText for Wear OS display.
 * This function should already exist in your codebase.
 */
private fun resolveWearOsSubText(
    context: Context,
    source: Notification,
    sourcePackageName: String
): String? {
    // Implementation: Extract subtext from notification extras
    // or resolve from app metadata
    return source.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
}

/**
 * Resolves the Wear OS app header (app name and icon).
 * This function should already exist in your codebase.
 */
private fun resolveWearOsAppHeader(
    context: Context,
    sourcePackageName: String
): WearOsAppHeader? {
    // Implementation: Get app name and icon from PackageManager
    return try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(sourcePackageName, 0)
        val appName = pm.getApplicationLabel(appInfo).toString()
        val icon = pm.getApplicationIcon(sourcePackageName)
        WearOsAppHeader(appName, IconCompat.createWithBitmap(drawableToBitmap(icon)))
    } catch (e: Exception) {
        null
    }
}

/**
 * Resolves the large icon bitmap for the app.
 */
private fun resolveAppLargeIconBitmap(
    context: Context,
    sourcePackageName: String
): Bitmap? {
    // Implementation: Extract large icon from app or notification
    return try {
        val pm = context.packageManager
        val icon = pm.getApplicationIcon(sourcePackageName)
        drawableToBitmap(icon)
    } catch (e: Exception) {
        null
    }
}

/**
 * Resolves the small icon from the source notification.
 */
private fun resolveSourceSmallIcon(
    context: Context,
    sbn: StatusBarNotification
): IconCompat? {
    // Implementation: Extract small icon from notification
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sbn.notification.smallIcon?.let { IconCompat.createFromIcon(context, it) }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Resolves the small icon from the app's metadata.
 */
private fun resolveAppSmallIcon(
    context: Context,
    sourcePackageName: String
): IconCompat? {
    // Implementation: Get app icon as fallback
    return try {
        val pm = context.packageManager
        val icon = pm.getApplicationIcon(sourcePackageName)
        IconCompat.createWithBitmap(drawableToBitmap(icon))
    } catch (e: Exception) {
        null
    }
}

/**
 * Rasterizes an IconCompat to a bitmap-based IconCompat to avoid
 * cross-package resource loading issues on Wear OS.
 */
private fun rasterizeSmallIcon(
    context: Context,
    icon: IconCompat?,
    fallbackIcon: IconCompat
): IconCompat {
    // Implementation: Convert icon to bitmap
    return icon ?: fallbackIcon
}

/**
 * Converts a Drawable to a Bitmap.
 */
private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        return drawable.bitmap
    }
    
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth.coerceAtLeast(1),
        drawable.intrinsicHeight.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * Data class representing Wear OS app header information.
 */
private data class WearOsAppHeader(
    val appName: String,
    val icon: IconCompat
)
