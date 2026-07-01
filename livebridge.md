# The Ultimate Architecture: Absolute Lockdown + RemoteInputHistory
We will combine the 10-second blackout window with the native `setRemoteInputHistory` API to FORCE Wear OS to show the right-aligned sent message.

1. **`ChatHistoryStore.kt`**:
   - Implement the 10-second lockdown based on `threadKey`.
   - Store `pendingRemoteInputText: String?` alongside the lock.

2. **`ReplyInterceptReceiver.kt`**:
   - Extract the reply text.
   - Call `ChatHistoryStore.setLockdownAndPendingText(threadKey, replyText, 10000L)`.
   - Call `LiveUpdateNotifier.forceUpdateChatUi(...)`.
   - Delay the execution of `pendingIntent.send()` by 500ms using a Handler to give Wear OS time to render the animation.

3. **`LiveUpdateNotifier.kt`**:
   - **The Blindfold:** In `onNotificationPosted` and `onNotificationRemoved`, if `ChatHistoryStore.isLockedDown(threadKey)` is true, `return` immediately. Ignore all incoming Messenger updates.
   - **The Force Update (`forceUpdateChatUi`):** Rebuild the notification strictly from the cache. 
   - **CRITICAL STEP:** When building the `NotificationCompat.Builder`, check if `ChatHistoryStore` has a `pendingRemoteInputText`. If it does, YOU MUST CALL `.setRemoteInputHistory(arrayOf(pendingText))`. This is the magic key that forces Wear OS to display the text on the right side natively.

# Constraints
- Keep `package com.kakao.taxi.liveupdate` or `package com.kakao.taxi` at the top of the files.
- The reply action MUST have `.setShowsUserInterface(false)` and `.setAllowGeneratedReplies(true)`.
- Use Kotlin. 
- DO NOT OUTPUT ANY GIT COMMANDS.