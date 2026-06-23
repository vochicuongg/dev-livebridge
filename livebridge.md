## YOUR TASK:
1. **Analyze:** Look at my `LiveUpdateNotifier.kt` (which I have provided) and identify where the logic is forcing a fallback to `BigTextStyle` or failing to correctly attach `MessagingStyle` for Zalo.
2. **Strategy:** I suspect the Zalo package logic is hardcoded to strip MessagingStyle or that my `addLocalEchoAndRefresh` is being immediately overwritten by a refresh event from Zalo.
3. **Requirement:** I do NOT want you to blindly overwrite files. I want you to:
   - Provide a high-level architectural plan to prevent "Đã gửi một tin nhắn" from overriding the cache.
   - Propose the exact modification to `buildMirroredNotification` to force native `MessagingStyle` rendering for Zalo/Messenger so bubbles appear correctly.
   - Explain how to ensure the "Me" bubble is consistently rendered as the local sender using `Person.Builder().setName("Me").build()`.

**Output Format:** Provide a detailed diagnostic report and a step-by-step logic plan. DO NOT provide the full file content yet. Once I approve your strategy, I will instruct you on how to apply it.