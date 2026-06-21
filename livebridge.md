Step-by-Step Instructions:
First, use your file editing tool to open the Live Update Notification Listener Service Kotlin file and the Live Update Notifier Kotlin file.
Second, locate the VIP bypass logic you injected earlier at the entry points (where the package contains zalo).
Third, you MUST preserve the bypasses for Silent priority, Low priority, Group Summary, Local Only, Ongoing, and Non-Clearable flags. These are critical for intercepting Chat Bubbles.
Fourth, REMOVE the bypass for the Deduplication/Anti-Spam tracker specifically. Zalo MUST be subjected to the standard text/title deduplication hash checks. If Zalo posts a background update with the exact same content as the previous one, LiveBridge must drop it and not fire a mirrored alert.
Finally, save both files and run the gradle compile debug kotlin command.

Output Format:
Do not output any code blocks or code snippets. Output only your confirmation that you used the file editing tool to remove the deduplication bypass while preserving the other VIP flag bypasses, and that the build compiled successfully.