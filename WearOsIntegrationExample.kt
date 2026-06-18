package com.kakao.taxi.liveupdate

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

/**
 * INTEGRATION EXAMPLE: How to use formatNameWithAppSubtitle in applyWearOsSourcePresentation
 * 
 * This file demonstrates the complete refactoring required to replace the old
 * text-based watermark approach (e.g., "[Zalo] Cường") with the new subtitle
 * formatting approach using SpannableString.
 */

/**
 * Example of how to refactor applyWearOsSourcePresentation function
 * 
 * OLD APPROACH (Text-based watermark):
 * - Concatenated app name: "[Zalo] Cường"
 * - Made names too long and unaesthetic on Wear OS
 * 
 * NEW APPROACH (Spannable subtitle):
 * - Original name on first line: "Cường"
 * - App name on second line (smaller, gray): "Zalo"
 */
private fun applyWearOsSourcePresentation(
    context: Context,
    builder: NotificationCompat.Builder,
    source: Notification,
    sourcePackageName: String
) {
    // Resolve the Wear OS app header (contains app name and icon)
    val appHeader = resolveWearOsAppHeader(context, sourcePackageName) ?: return
    val appName = appHeader.appName
    
    // --- MessagingStyle extraction & rebuild for Wear OS Chat UI ---
    try {
        val sourceStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(source)
        
        if (sourceStyle != null) {
            // Get original user, conversation title, and messages
            val originalUser = sourceStyle.user
            val originalConversationTitle = sourceStyle.conversationTitle
            val originalMessages = sourceStyle.messages.takeLast(MAX_WEAR_MESSAGING_STYLE_MESSAGES)
            val isGroupConversation = sourceStyle.isGroupConversation
            
            // ====================================================================
            // STEP 1: Format the User (device owner) name with app subtitle
            // ====================================================================
            val formattedUserName = formatNameWithAppSubtitle(
                originalName = originalUser?.name,
                appName = appName
            )
            
            val newUser = Person.Builder()
                .setName(formattedUserName)  // Apply formatted name with subtitle
                .setKey(originalUser?.key)
                .setIcon(originalUser?.icon)
                .setBot(originalUser?.isBot == true)
                .setImportant(originalUser?.isImportant == true)
                .build()
            
            // Create new MessagingStyle with formatted user
            val newStyle = NotificationCompat.MessagingStyle(newUser)
            newStyle.isGroupConversation = isGroupConversation
            
            // ====================================================================
            // STEP 2: Format the Conversation Title (for group chats) with app subtitle
            // ====================================================================
            if (isGroupConversation && !originalConversationTitle.isNullOrBlank()) {
                val formattedTitle = formatNameWithAppSubtitle(
                    originalName = originalConversationTitle,
                    appName = appName
                )
                newStyle.conversationTitle = formattedTitle
            }
            
            // ====================================================================
            // STEP 3: Rebuild messages with formatted sender names
            // ====================================================================
            for (message in originalMessages) {
                val originalSender = message.person
                val messageText = message.text
                val messageTimestamp = message.timestamp
                
                // Format the sender name with app subtitle
                val formattedSenderName = if (originalSender != null) {
                    formatNameWithAppSubtitle(
                        originalName = originalSender.name,
                        appName = appName
                    )
                } else {
                    null
                }
                
                // Build new Person with formatted name
                val newSender = if (originalSender != null) {
                    Person.Builder()
                        .setName(formattedSenderName)  // Apply formatted name with subtitle
                        .setKey(originalSender.key)
                        .setIcon(originalSender.icon)
                        .setBot(originalSender.isBot)
                        .setImportant(originalSender.isImportant)
                        .build()
                } else {
                    null
                }
                
                // Add message with formatted sender
                newStyle.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        messageText,
                        messageTimestamp,
                        newSender
                    )
                )
            }
            
            // Apply the rebuilt MessagingStyle to the builder
            builder.setStyle(newStyle)
            
            // Set large icon from app header
            builder.setLargeIcon(appHeader.icon.getBitmap())
            
            return
        }
    } catch (e: Exception) {
        android.util.Log.w("LiveUpdateNotifier", "applyWearOsSourcePresentation: failed to rebuild MessagingStyle", e)
    }
    
    // ====================================================================
    // STEP 4: Fallback Builder (for non-MessagingStyle notifications)
    // ====================================================================
    // If MessagingStyle extraction failed or not applicable, format the content title
    val originalTitle = source.extras.getCharSequence(Notification.EXTRA_TITLE)
    if (!originalTitle.isNullOrBlank()) {
        val formattedTitle = formatNameWithAppSubtitle(
            originalName = originalTitle,
            appName = appName
        )
        builder.setContentTitle(formattedTitle)
    }
    
    // Set large icon from app header
    builder.setLargeIcon(appHeader.icon.getBitmap())
}

/**
 * Helper function to resolve Wear OS app header
 * This function should already exist in your codebase
 */
private fun resolveWearOsAppHeader(
    context: Context,
    sourcePackageName: String
): WearOsAppHeader? {
    // Implementation should retrieve app name and icon
    // This is just a placeholder showing the expected return type
    return null
}

/**
 * Data class for Wear OS app header
 * This should already exist in your codebase
 */
private data class WearOsAppHeader(
    val appName: String,
    val icon: IconCompat
)

/**
 * Extension function to safely get bitmap from IconCompat
 */
private fun IconCompat.getBitmap(): android.graphics.Bitmap? {
    return try {
        // Implementation depends on IconCompat version
        null
    } catch (e: Exception) {
        null
    }
}

private const val MAX_WEAR_MESSAGING_STYLE_MESSAGES = 10
