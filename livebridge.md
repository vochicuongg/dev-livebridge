# The 100% Native Injection Architecture
We must completely abandon rebuilding the notification from scratch for the local echo. We must use a "Clone and Inject" (Native Payload Injection) pattern to preserve 100% of the active notification's OEM extras, `setWhen` timestamp, and internal `Person` objects.

1. **`ChatHistoryStore.kt`**:
   - Must temporarily cache the actual `android.app.Notification` object currently displayed on the watch for each `threadKey`.

2. **`ReplyInterceptReceiver.kt` & `LiveUpdateNotifier.kt` (The Injection)**:
   - When the user replies, retrieve the `activeNotification` from `ChatHistoryStore`.
   - Safely recover the builder: `val builder = NotificationCompat.Builder(context, activeNotification)`.
   - Extract the existing style: `val recoveredStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(activeNotification)`.
   - **THE MAGIC INJECTION:** Append the local echo directly to the recovered style: `recoveredStyle?.addMessage(replyText, System.currentTimeMillis(), null as Person?)`.
   - Reapply the updated style: `recoveredStyle?.setBuilder(builder)`.
   - Set `.setOnlyAlertOnce(true)` so the watch doesn't buzz again. DO NOT explicitly change `.setWhen()`.
   - Call `notificationManager.notify(TAG, ID, builder.build())`.
   - Finally, run the 10-second lockdown and the 500ms delayed `PendingIntent.send()`.

# Expected Output
Provide the refactored Kotlin code for `LiveUpdateNotifier.kt` (specifically the local echo update function) and `ReplyInterceptReceiver.kt` implementing this exact `extractMessagingStyleFromNotification` and cloning pattern.
Remove the `setRemoteInputHistory` logic as it conflicts with this approach.

# Constraints
- Keep `package com.kakao.taxi.liveupdate` or `package com.kakao.taxi` at the top.
- DO NOT OUTPUT ANY GIT COMMANDS. Use Kotlin.