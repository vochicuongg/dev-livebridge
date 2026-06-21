Step-by-Step Instructions:
First, use your tool to open the Live Update Notifier Kotlin file.
Second, locate the Zalo bridging path where you previously added the grouping synchronization (the set group and set sort key methods).
Third, we must make the mirrored group key unique so it survives the original notification's destruction. Extract the source notification's group string. If it is null, default to the string "zalo_standalone".
Fourth, concatenate the string "_livebridge_safe" to the end of that group string.
Fifth, pass this newly combined unique string into the builder's set group method.
Sixth, do the exact same concatenation for the sort key: extract the source sort key, append "_livebridge_safe" to it, and pass it to the builder's set sort key method.
Finally, save the file and run the gradle compile debug kotlin command to ensure the syntax is perfectly correct.

Output Format:
Do not output any code blocks. Output only your confirmation that you used the file editing tool to decouple the group and sort keys by appending the unique suffix, and that the build compiled successfully.