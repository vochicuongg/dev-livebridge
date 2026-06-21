Step-by-Step Instructions:
First, use your file editing tool to open the Live Update Notification Listener Service Kotlin file and the Live Update Notifier Kotlin file.
Second, locate the absolute entry points where notifications are first evaluated (such as the top of on Notification Posted and the top of the maybe mirror function).
Third, identify all early-return filters. These typically check for conditions like priority being lower than default, the notification being a group summary, the notification belonging to a silent channel, or deduplication string matches.
Fourth, inject an absolute VIP Bypass for Zalo. Add logic stating that if the source package name lower string contains the word zalo, it MUST bypass the silent filter, bypass the low priority filter, bypass the group summary drop, and bypass the deduplication tracker.
Fifth, guarantee that if the package is Zalo, the function explicitly proceeds directly to the build mirrored notification function without returning early.
Finally, save both files and run the gradle compile debug kotlin command.

Output Format:
Do not output any code blocks. Output only your confirmation that you used the file editing tool to inject the absolute inbound bypass for Zalo, specifically listing which filters you bypassed, and that the build compiled successfully.