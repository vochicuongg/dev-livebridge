## Step-by-Step Instructions:

1. **Analyze the Project Structure:**
   Identify where LiveBridge handles the reply action coming from the Wear OS `RemoteInput`. Search for code referencing `RemoteInput.getResultsFromIntent(intent)` or where input text from the wearable is captured right before triggering the source app's reply `PendingIntent`.

2. **Implement Universal Local Echo Logic:**
   Once you locate the exact intercept point of the user's typed response text:
   - Extract the typed text string dynamically.
   - Extract the target package name and the specific conversation/thread identity from the handling Intent or context.
   - Map these variables to find the exact matching active cache key inside `LiveUpdateNotifier.conversationHistoryCache`.

3. **Inject the "Me" Bubble Globally:**
   - Instantiate a generic `NotificationCompat.MessagingStyle.Message` containing the typed reply text, the current system timestamp (`System.currentTimeMillis()`), and a local user `Person` object designated as "Me" (or null).
   - Append this generated Message directly into `LiveUpdateNotifier.conversationHistoryCache` for that specific thread.

4. **Trigger Instant Wearable Refresh:**
   Immediately following the cache injection, execute the existing rebundling/notification firing sequence in `LiveUpdateNotifier`. This forces the Wear OS sync engine to instantly render the user's text on the right side.

5. **Apply the Modifications:**
   Write the precise changes into the target Kotlin files using your filesystem writing tools.

## Output Format:
- I acknowledge that your environment cannot execute Gradle builds. 
- You ARE ALLOWED to output code snippets and diffs to clearly illustrate the exact architecture you modified to handle the `RemoteInput` and manipulate the global cache. 
- Provide a summary of the edits once completed, and I will execute `gradle compileDebugKotlin` locally.