Step-by-Step Instructions:
First, use your tool to open the Live Update Notifier Kotlin file.
Second, locate the Zalo bridging path at the bottom of the build mirrored notification function.
Third, implement Grouping Synchronization: Call the set group method on the builder and pass the group from the source notification. Call the set sort key method on the builder and pass the sort key from the source notification. This ensures the OS properly stacks rapid messages instead of choking.
Fourth, implement Rate-Limit Prevention: Change the set only alert once method argument from false to true. This stops the OS from penalizing LiveBridge for playing too many sounds in a row, allowing silent background updates to flow freely to the watch.
Fifth, implement Timestamp Refresh: Call the set when method on the builder and pass the current system time in milliseconds. Chat apps often recycle old timestamps, causing the OS to bury the notification at the bottom of the queue.
Sixth, implement Deduplication Bypass: Scroll up to the very top of the file to the maybe mirror function. Locate the internal deduplication or anti-spam logic blocks. Inject a bypass condition so that if the package name contains zalo, it completely skips the deduplication phase and is guaranteed to proceed.
Finally, save the file and run the gradle compile debug kotlin command.

Output Format:
Do not output any code blocks. Output only your confirmation that you used the file editing tool to successfully apply the grouping, rate-limit, timestamp, and deduplication fixes, and that the build compiled successfully.