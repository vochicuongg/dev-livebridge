## Step-by-Step Instructions
1. Locate the final "else" fallback block in the "buildMirroredNotification" function (where non-messaging notifications are handled).
2. Extract the string value from the source notification's "tickerText" property. Trim it and ensure its length is greater than 1.
3. Update the resolution of your fallback text variable. You must prioritize this newly extracted ticker string right after checking for hidden message text, but before falling back to the standard clean text and collected text.
4. If a valid fallback text is resolved from this chain, apply it using the content text and big text style methods as you currently do.
5. CRITICAL NEW STEP: At the very end of this "else" block (outside of the fallback text null-check), explicitly add all metadata from the source notification's "extras" bundle into the builder using the builder's "addExtras" method. This guarantees that Wear OS receives all original native templates and rendering hints.

## Output Format
Analyze this directive and output ONLY the refactored fallback block using strict logical implementation.