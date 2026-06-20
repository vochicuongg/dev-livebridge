Step-by-step Instructions for File Editing:

Revert the early return: Open the file and completely remove the early return block that excludes Zalo from the top of the maybe mirror function. Let Zalo proceed normally through the filters.

Re-insert a safe, simplified Zalo branch: Inside the main notification builder routing block (where messaging style and otp override are checked), add a dedicated branch checking if the source package name lower string contains zalo.
Inside this branch:

Resolve the text by preferring the text variable, falling back to display text, then ticker text, then a default 'New message' string.

Set the builder content title to the display title.

Set the builder content text and ticker to this resolved text.

Set the builder style to a new big text style using this resolved text.

Strip all remote views by passing null to the custom content view, custom big content view, and custom heads up content view setters.

Strip toxic extras by calling remove on the builder extras for text lines, template, and messages constants.

Nullify the category.

Call the add reply action if not already copied function.

Fix the Wear OS bridging block at the bottom: Scroll down to the should remove original condition. Inside this block, delete the current broken logic and implement a strict split using an if and else statement checking if the source package name lower string contains zalo.

Inside the Zalo bridging path:

Call set channel id with the alerts channel.

Call set local only false and set ongoing false.

Call set only alert once false, set silent false, set priority high, and set defaults all.

Create a new empty wearable extender, map and add the source actions to it, and apply it to the builder.
(CRITICAL: We strictly avoid applying the wear os source presentation and original text overrides here to protect our big text style).

Inside the Else bridging path (for Messenger and everything else):

Call set channel id with the alerts channel.

Call set local only false and set ongoing false.

Call the apply wear os source presentation function.

Evaluate if it is a chat app. If true, set alert once false, silent false, priority high, and defaults all. If false, set alert once true.

Extract the original title and text from the source extras.

Override the builder content title and content text with these original values.

Create a new wearable extender, map and add the source actions to it, and apply it to the builder.

Output Format:
Do not output any code blocks. Output only your confirmation that you used your file editing tool to apply these exact structural fixes and that the build compiled successfully.