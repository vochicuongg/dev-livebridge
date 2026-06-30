# The Architecture: Absolute Local Truth & UI Lock
1. **The Master Cache (`ChatHistoryStore`):** This is the absolute source of truth. It holds `MutableList<Message>`. We NEVER delete messages from this cache unless the thread is genuinely old.
2. **The Intercept & Force Update (`ReplyInterceptReceiver`):** - When the user replies, append the sent text to `ChatHistoryStore` with `Person = null` (isMe = true).
   - Set a `uiLockTimestamp` in `ChatHistoryStore` for this thread (e.g., `currentTime + 6000ms`).
   - AGGRESSIVELY call a local UI update method (e.g., `LiveUpdateNotifier.forceUpdateChatUi()`) to re-render the notification immediately from the cache.
   - Forward the PendingIntent.
3. **The Shield (`LiveUpdateNotifier`):**
   - In `onNotificationPosted`: 
     - If the incoming notification has NO `MessagingStyle` (the "Sending..." downgrade) AND the `uiLockTimestamp` for this thread is still active (within 6 seconds), **DROP THE UPDATE ENTIRELY** (`return`). Do not let it collapse the UI.
     - If it HAS a `MessagingStyle`, extract messages, fuzzy-merge them into `ChatHistoryStore` (Append-Only, never overwrite our local echo), and render from the cache.
   - In `onNotificationRemoved`:
     - If the `uiLockTimestamp` is active, **IGNORE THE REMOVAL**. 

# Expected Output
Please provide the refactored Kotlin code for:
1. `ChatHistoryStore.kt`: Add the `uiLockTimestamp` logic.
2. `ReplyInterceptReceiver.kt`: Extract text, save to cache, set the lock, FORCE the UI update via Notifier, then send the intent.
3. `LiveUpdateNotifier.kt`: Implement "The Shield" in `onNotificationPosted` and `onNotificationRemoved` that drops state-downgrades during the lock period. Create the `forceUpdateChatUi` method that builds the `MessagingStyle` strictly from the cache.

# Constraints
- Keep `package com.kakao.taxi.liveupdate` or `package com.kakao.taxi` at the top of the files.
- The reply action MUST have `.setShowsUserInterface(false)` and `.setAllowGeneratedReplies(true)`.
- Use Kotlin. 
- DO NOT OUTPUT ANY GIT COMMANDS.