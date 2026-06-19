## Your Task

1. **RETAIN THE MESSAGING APP FIX:** Keep the logic where we check `if (NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(source) != null)`. Keep the `buildChatHistory` execution and the extraction of the Reply (`RemoteInput`) action. This is our ONLY custom addition and it must remain intact.

2. **REVERT THE NON-MESSAGING FALLBACK (THE `else` BLOCK):**
   Locate the final routing block in `buildMirroredNotification` (after `if (hasProgress)` and `else if (otpOverride != null)`).
   
   If the notification is NOT a MessagingStyle, it must fall into the final `else` block. 
   You must **DELETE** any custom logic we added there in previous turns. 
   
   You must **RESTORE** this exact fallback block to be 100% identical to the unmodified `LiveUpdateNotifier.kt` from the original GitHub repository.

   Do not inject setContentTitle, setContentText(displayText), or anything else unless it was explicitly written by the original author in that exact spot.

   Output Format
Do not output the entire file. Output ONLY the code snippet containing the if/else routing block at the end of buildMirroredNotification.
Show how our custom MessagingStyle check seamlessly branches off, while all other notifications fall into the perfectly restored original else block.