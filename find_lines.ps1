$lines = Get-Content 'android\app\src\main\kotlin\com\appsfolder\livebridge\liveupdate\LiveUpdateNotifier.kt'
for($i=0; $i -lt $lines.Count; $i++) {
    if($lines[$i] -match 'fun maybeMirror|fun addLocalEchoAndRefresh|setRemoteInputHistory|fun recordReplyDebounce|pendingReplyText|CHAT_APP_PACKAGES') {
        Write-Output ("{0}: {1}" -f ($i+1), $lines[$i].Trim())
    }
}
