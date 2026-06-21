Step-by-Step Instructions:
First, use your file editing tool to open the Live Update Notifier Kotlin file.
Second, scroll down to the bottom of the build mirrored notification function and locate the should remove original block.
Third, go inside the Else branch of this bridging block (the path that executes for non-Zalo packages).
Fourth, find the exact line where the original title is extracted from the source extras (e.g., getting the EXTRA_TITLE char sequence).
Fifth, immediately after extracting this original title, check if the source package name lower string contains whatsapp.
Sixth, if it is WhatsApp, apply the exact same Regex replacement that you used at the top of the function to clean this original title variable.
Seventh, ensure that the builder content title is overridden using this newly cleaned original title, rather than the raw one.
Finally, save the file and run the gradle compile debug kotlin command.

Output Format:
Do not output any code blocks. Output only your confirmation that you used the file editing tool to apply the exact same regex cleanup to the original title override inside the Wear OS bridging block, and that the build compiled successfully.