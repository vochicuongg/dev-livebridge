$file = "android\app\src\main\kotlin\com\appsfolder\livebridge\liveupdate\LiveUpdateNotificationListenerService.kt"
$patterns = @(
    'onNotificationRemoved',
    'cancelMirrored',
    'handleMirroredRemoved',
    'clearReplyHistory',
    'isWithinReplyGrace'
)
$combined = ($patterns -join '|')
$lines = Get-Content $file
for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match $combined) {
        Write-Output ("{0}: {1}" -f ($i + 1), $lines[$i].Trim())
    }
}
