## Step-by-Step Instructions
1. **Fix the Listener Service:**
   Open `LiveUpdateNotificationListenerService.kt`. Examine `onNotificationPosted`. If you added ANY early return or bypass logic that checks for "zalo" inside `onNotificationPosted`, **DELETE IT IMMEDIATELY**. Zalo must be allowed to enter the mirroring pipeline. (Leave the bypass in `onNotificationRemoved` intact).
   
2. **Fix the Builder Grouping (Decouple completely):**
   Open `LiveUpdateNotifier.kt`. Locate the Zalo bridging path where you previously added the `_livebridge_safe` string concatenations for the group and sort keys.
   **DELETE** all logic related to extracting the group key and appending `_livebridge_safe`.
   Instead, completely decouple the notification by explicitly calling:
   - `builder.setGroup(null)`
   - `builder.setSortKey(null)`
   By removing the group entirely, the mirrored notification becomes a true standalone alert. It will perfectly survive the deletion of the original notification group without being silently dropped by the OS for missing a Group Summary.

3. **Verify Alert Flags:**
   Ensure that the dynamic alert flag is still correctly applied:
   `val sourceAlertOnce = source.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0`
   `builder.setOnlyAlertOnce(sourceAlertOnce)`

4. **Save and Build:**
   Save both files and run the `gradle compileDebugKotlin` command.

## Output Format
Do not output any code blocks. Output ONLY your confirmation that you removed any rogue bypasses in `onNotificationPosted`, replaced the invalid group key logic with `setGroup(null)`, and that the build compiled successfully.