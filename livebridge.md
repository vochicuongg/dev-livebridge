1. **Deterministic `Person` Object:** In `LiveUpdateNotifier`, the host user MUST be created with a hardcoded, deterministic key:
   `val me = Person.Builder().setName(cachedSelfName ?: "Tôi").setKey("livebridge_self_user_key").build()`
2. **Synthetic Style Rebuild:**
   - Instantiate `val style = NotificationCompat.MessagingStyle(me)`.
   - Iterate through `ChatHistoryStore`'s cached messages. 
   - For received messages, rebuild their `Person` object with a deterministic key based on the sender's name.
   - For the local echo (the user's sent message), pass `null` as the `Person` parameter. (This triggers the blue bubble).
3. **Thread Identity Locks:** The rebuilt `NotificationCompat.Builder` MUST explicitly bind the thread identity:
   - `.setGroup(threadKey)`
   - `.setSortKey(threadKey)`
   - `.setOnlyAlertOnce(true)`
4. **The Update Flow (`ReplyInterceptReceiver`)**:
   - Save the reply text to `ChatHistoryStore` with `isMe = true`.
   - Call `LiveUpdateNotifier.forceUpdateChatUi(threadKey)` immediately to trigger the Deterministic Rebuild.
   - Run the 10-second lockdown.
   - Fire the `PendingIntent.send()` with a 500ms delay to ensure the watch renders the UI first.

# Expected Output
Provide the refactored Kotlin code for the deterministic rebuilding logic in `LiveUpdateNotifier.kt` (specifically the `forceUpdateChatUi` method) and the exact trigger flow in `ReplyInterceptReceiver.kt`. 

# Constraints
- Keep `package com.kakao.taxi.liveupdate` or `package com.kakao.taxi` at the top.
- The reply action MUST have `.setShowsUserInterface(false)` and `.setAllowGeneratedReplies(true)`.
- DO NOT OUTPUT ANY GIT COMMANDS. Use Kotlin.