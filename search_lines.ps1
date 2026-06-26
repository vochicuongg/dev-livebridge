$file = "android\app\src\main\kotlin\com\appsfolder\livebridge\liveupdate\LiveUpdateNotifier.kt"
$patterns = @(
    'conversationHistoryCache\.remove',
    'replyHistoryByMirrorKey\.remove',
    'fun clearReply',
    'fun cancelMirrored',
    'fun handleMirroredRemoved',
    'fun addLocalEcho',
    'fun buildMirroredNot',
    'fun mergeForMirror',
    'fun mergeAndGet',
    'lastLocalReply',
    'LOCAL_REPLY_GRACE',
    'stampLocalReply',
    'isWithinReplyGrace',
    'replyHistoryByMirrorKey\s*=',
    'conversationHistoryCache\s*='
)
$combined = ($patterns -join '|')
$lines = Get-Content $file
for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match $combined) {
        Write-Output ("{0}: {1}" -f ($i + 1), $lines[$i].Trim())
    }
}
