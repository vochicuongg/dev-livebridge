Step-by-Step Instructions:

Use your file editing tool to open the Live Update Notifier Kotlin file.

Revert Dynamic IDs: Locate the notify mirrored notification function (or wherever the notification manager notify method is called). Completely remove the dynamic ID generation logic that adds the current time millis to the ID. Revert it to strictly use the deterministic source notification ID so that the OS can properly stack and group updates again.

Refactor Chat UI: Locate the chat bridging paths (like Zalo, Messenger, etc.) where you previously applied the custom chat history using the Big Text Style.

Delete all Big Text Style application logic for these chat apps entirely.

Construct a robust native Notification Compat Messaging Style. Define the local user by creating a Person Builder with the name set to "Me". Instantiate the Messaging Style using this local user person object.

If a conversation title exists, set it on the messaging style.

Iterate through the cached conversation history messages. For messages received from the other person, add them to the messaging style and attach their name via a new Person object. For messages sent by the local user, add them to the messaging style but attach the "Me" Person object. This natively forces Wear OS to render the user's messages on the RIGHT side in a distinct chat bubble.

Apply this Messaging Style directly to the notification builder.

Save the file and run the gradle compile debug kotlin command.

Output Format:
Do not output any code blocks. Output ONLY your confirmation that you reverted the dynamic ID to restore native stacking, replaced the flat text style with a native Messaging Style to trigger left/right chat bubbles, and that the build compiled successfully.