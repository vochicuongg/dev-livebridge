Step-by-Step Instructions:
First, use your file editing tool to open the Live Update Notification Listener Service Kotlin file and the Live Update Notifier Kotlin file.
Second, locate the exact VIP bypass logic you injected previously at the entry points (on notification posted and maybe mirror).
Third, you must explicitly expand this bypass to nullify three additional drop conditions.
Fourth, inject logic to bypass the Local Only check: if the package contains zalo, do NOT drop the notification even if the local only flag is present.
Fifth, inject logic to bypass the Ongoing check: if the package contains zalo, do NOT drop the notification even if it is marked as an ongoing event or foreground service.
Sixth, inject logic to bypass the Clearable check: if the package contains zalo, do NOT drop the notification even if it cannot be swiped away by the user.
Finally, save both files and run the gradle compile debug kotlin command.

Output Format:
Do not output any code blocks. Output only your confirmation that you used the file editing tool to upgrade the VIP bypass (specifically naming the Local Only, Ongoing, and Non-Clearable bypasses), and that the build compiled successfully.