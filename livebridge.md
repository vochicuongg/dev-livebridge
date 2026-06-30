# Expected Output
Please refactor `ReplyInterceptReceiver.kt`, `LiveUpdateNotifier.kt`, and `ChatHistoryStore` (or equivalent caching mechanism) with the following logic:

1. **`ReplyInterceptReceiver.kt` (Dumb Forwarder + State Tracker):**
   - Extract the `replyText` and `threadKey` (or notification ID).
   - Save this to `ChatHistoryStore.setPendingReply(threadKey, replyText)`.
   - Forward the `PendingIntent` directly to the target app (Messenger/Zalo). 
   - DO NOT call any UI update (`notify()`) here. Let the target app do it.

2. **`ChatHistoryStore` Updates:**
   - Add a way to store and retrieve `pendingReplyText` and `pendingReplyTimestamp` for a given thread.

3. **`LiveUpdateNotifier.kt` (Smart Merging & Injection):**
   - Inside `onNotificationPosted`, extract the `MessagingStyle` history.
   - **Crucial Step:** Check if `ChatHistoryStore` has a `pendingReplyText` for this thread. 
   - If a pending reply exists AND the target app's parsed `MessagingStyle` does NOT yet contain this exact text (meaning it's still in the "Sending..." phase), artificially **append** a new `Message` object containing the `pendingReplyText` to the history array with `Person = null` (so Wear OS renders it as a blue right-aligned bubble).
   - If the target app's parsed `MessagingStyle` DOES contain the text (meaning it successfully sent and officially added it to the thread), clear the pending reply state in `ChatHistoryStore`.
   - **Self-Sender Normalization:** Continue to strictly enforce that if `senderName` matches "You", "Bạn", "Me", or `messagingStyle.userDisplayName`, the `Person` object MUST be set to `null` before building the notification.

4. **Debounce Deletion:**
   - Keep the existing logic: If `onNotificationRemoved` fires within 4 seconds of a reply, ignore it to prevent the UI from collapsing if the target app temporarily cancels the notification.

# Constraints
- Keep `package com.kakao.taxi.liveupdate` or `package com.kakao.taxi` at the top of the files.
- Ensure the `NotificationCompat.Action` for replying includes `.setShowsUserInterface(false)` and `.setAllowGeneratedReplies(true)`.
- DO NOT OUTPUT ANY GIT COMMANDS.
- Use Kotlin. Provide robust, production-ready code.