Step-by-Step Instructions:
First, use your tool to open the Live Update Notifier Kotlin file.
Second, locate the Zalo bridging path at the bottom of the build mirrored notification function (inside the should remove original block).
Third, REMOVE the hardcoded method calls for "set only alert once", "set defaults", and "set silent". We must stop forcefully overriding the OS sound and vibration channels.
Fourth, extract the dynamic alert-once boolean from the source notification. Check if the source notification flags integer contains the bitwise constant for FLAG ONLY ALERT ONCE.
Fifth, apply this extracted dynamic boolean to the builder using the set only alert once method. This guarantees LiveBridge only suppresses popups when Zalo natively wants to suppress them.
Sixth, ensure the set priority method is still explicitly set to high (or max) to allow Heads-Up popups to render when the alert-once flag evaluates to false.
Finally, save the file and run the gradle compile debug kotlin command to verify the build.

Output Format:
Do not output any code blocks. Output only your confirmation that you used the file editing tool to remove the hardcoded overrides, implemented the dynamic flag inheritance, and achieved a successful build.