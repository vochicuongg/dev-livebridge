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
    val actions = sbn.notification.actions ?: return null
    
    // Find the first action that has RemoteInput (this is the Reply action)
    return actions.firstOrNull { action ->
        action.remoteInputs != null && action.remoteInputs.isNotEmpty()
    }?.let { frameworkAction ->
        // Convert framework Action to NotificationCompat.Action
        try {
            NotificationCompat.Action.Builder(
                frameworkAction.icon,
                frameworkAction.title,
                frameworkAction.actionIntent
            ).apply {
                // Copy RemoteInputs - this is the critical part for Wear OS Reply functionality
                frameworkAction.remoteInputs?.forEach { remoteInput ->
                    addRemoteInput(
                        RemoteInputCompat.fromPlatform(remoteInput)
                    )
                }
                
                // Copy extras if any
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    frameworkAction.extras?.let { addExtras(it) }
                }
                
                // Copy other properties
                setAllowGeneratedReplies(frameworkAction.allowGeneratedReplies)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setSemanticAction(frameworkAction.semanticAction)
                }
            }.build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert Reply action", e)
            null
        }
    }
}

// ============================================================
// REFACTORED FUNCTION: Build Chat History with BigTextStyle
// ============================================================

/**
 * Applies Wear OS-specific presentation using BigTextStyle instead of MessagingStyle.
 * This gives us full control over text formatting (inserting app names, styling, etc.)
 * while preserving the Reply action for Wear OS interaction.
 */
private fun applyWearOsSourcePresentation(
    context: Context,
    builder: NotificationCompat.Builder,
    sbn: StatusBarNotification,
    sourceLargeIcon: Bitmap?
) {
    val source = sbn.notification
    val sourcePackageName = sbn.packageName
    
    // --- Extract MessagingStyle to build chat history with BigTextStyle ---
    try {
        val extractedStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(source)

        if (extractedStyle != null) {
            // 1. GET APP NAME
            val appNameStr = resolveWearOsSubText(context, source, sourcePackageName)
                ?: resolveWearOsAppHeader(context, sourcePackageName)?.appName
                ?: "App"
            
            val conversationTitle = extractedStyle.conversationTitle?.toString() ?: ""
            
            // 2. BUILD CHAT HISTORY USING SpannableStringBuilder
            val chatHistorySpannable = android.text.SpannableStringBuilder()
            
            // Get recent messages (limit to avoid OOM on watch)
            val messages = extractedStyle.messages
            val recentMessages = if (messages.size > MAX_WEAR_MESSAGING_STYLE_MESSAGES) {
                messages.takeLast(MAX_WEAR_MESSAGING_STYLE_MESSAGES)
            } else {
                messages
            }
            
            // Build chat history: "Sender: Message\nSender2: Message2..."
            recentMessages.forEachIndexed { index, message ->
                if (index > 0) {
                    chatHistorySpannable.append("\n")
                }
                
                // Get sender name
                val senderName = message.person?.name?.toString()
                    ?: message.senderPerson?.name?.toString()
                    ?: extractedStyle.user.name?.toString()
                    ?: "Unknown"
                
                // Append sender name in BOLD
                val startPos = chatHistorySpannable.length
                chatHistorySpannable.append(senderName)
                chatHistorySpannable.append(": ")
                chatHistorySpannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    startPos,
                    chatHistorySpannable.length - 2, // Don't bold the ": "
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                // Append message text
                chatHistorySpannable.append(message.text ?: "")
            }
            
            // 3. SET TITLE with App Name and Conversation Title
            val titleText = if (conversationTitle.isNotEmpty()) {
                "$appNameStr - $conversationTitle"
            } else {
                appNameStr
            }
            builder.setContentTitle(titleText)
            
            // 4. SET BigTextStyle with chat history
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(chatHistorySpannable)
            )
            
            // 5. EXTRACT AND ADD REPLY ACTION (CRITICAL for Wear OS)
            val replyAction = extractReplyAction(sbn)
            if (replyAction != null) {
                // Add to builder
                builder.addAction(replyAction)
                
                // Also add to WearableExtender for optimal Wear OS display
                val wearableExtender = NotificationCompat.WearableExtender()
                wearableExtender.addAction(replyAction)
                builder.extend(wearableExtender)
            }
            
            // 6. KEEP AVATAR (LargeIcon) - Extract from last message sender if available
            if (sourceLargeIcon == null) {
                val lastSenderIcon = recentMessages
                    .lastOrNull { it.person?.icon != null }
                    ?.person?.icon
                lastSenderIcon?.let { icon ->
                    runCatching {
                        icon.toIcon(context)?.let { frameworkIcon ->
                            iconToBitmap(context, frameworkIcon)?.let(builder::setLargeIcon)
                        }
                    }
                }
            }
        } else {
            // Fallback: not a MessagingStyle notification — keep original
            // BigTextStyle or no style (don't force Chat UI)
            val extras = source.extras
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
            if (bigText != null) {
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(bigText)
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "applyWearOsSourcePresentation: failed to rebuild with BigTextStyle", e)
    }

    sourceLargeIcon?.let(builder::setLargeIcon)

    builder.addExtras(Bundle(source.extras))

    // ============================================================
    // WEAR OS SMALL ICON FIX: Rasterize to Bitmap to bypass
    // cross-package resource loading restrictions
    // ============================================================
    val originalSmallIcon = try {
        // Priority 1: Extract small icon directly from source notification
        resolveSourceSmallIcon(context, sbn)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to extract original small icon", e)
        null
    }

    val fallbackSmallIcon = if (originalSmallIcon == null) {
        try {
            // Priority 2: Fallback to application icon via PackageManager
            resolveAppSmallIcon(context, sourcePackageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve app small icon fallback", e)
            null
        }
    } else {
        null
    }

    // Rasterize the icon to Bitmap to avoid TYPE_RESOURCE cross-package issues
    val rasterizedSmallIcon = rasterizeSmallIcon(
        context = context,
        icon = originalSmallIcon ?: fallbackSmallIcon,
        fallbackIcon = IconCompat.createWithResource(context, R.drawable.ic_stat_liveupdate)
    )
    
    if (rasterizedSmallIcon != null) {
        builder.setSmallIcon(rasterizedSmallIcon)
        
        // Preserve original notification color tint to prevent icon from appearing black
        // (important for mask-style alpha-only icons)
        val originalColor = source.color
        if (originalColor != 0) {
            builder.setColor(originalColor)
        }
    }

    // Resolve SubText for Wear OS display (app name fallback)
    val resolvedSubText = resolveWearOsSubText(context, source, sourcePackageName)
    resolvedSubText?.let { builder.setSubText(it) }

    builder.setColor(NotificationCompat.COLOR_DEFAULT)
}
