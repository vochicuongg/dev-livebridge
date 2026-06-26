$file = "android\app\src\main\kotlin\com\appsfolder\livebridge\liveupdate\LiveUpdateNotifier.kt"
$patterns = @(
    'cachedMessages',
    'nativeMessagingStyle',
    'mergeAndGetCachedMessages',
    'mergeForMirror',
    'routedPerson',
    'clonedMessage',
    'addMessage'
)
$combined = ($patterns -join '|')
$lines = Get-Content $file
for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match $combined) {
        Write-Output ("{0}: {1}" -f ($i + 1), $lines[$i].Trim())
    }
}
