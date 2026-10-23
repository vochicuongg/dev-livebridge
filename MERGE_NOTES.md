# Merge notes — upstream appsfolder/livebridge v2.2.1 → fork

Branch: `merge-upstream-2.2.0` (NOT merged into `main` — build & test first)

## Why a manual 3-way merge

The fork is not a git fork of upstream (no shared commits): it was created
from a source snapshot. The merge therefore used upstream commit `16960ad`
(2026-05-16, the last upstream commit before this repo was created) as the
merge base against fork `main` (`07a7e1b`) and upstream `main` (`106ae2e`,
v2.2.1 + UI hotfix). 17 files conflicted; every hunk was resolved by hand.

## Merge principles applied

- Upstream v2.2 architecture is the base: policy classes
  (NotificationProcessing, *Policy, SourceChannel*, WearOsLiveUpdatesPolicy),
  unit tests, dictionary editor, text filters, source channels, ES/DE locales.
- The fork's identity is preserved everywhere:
  `namespace = com.kakao.taxi`, `applicationId = com.locnall.KimGiSa`
  (Samsung Now Bar whitelist spoof). All upstream Kotlin files were
  re-packaged `com.appsfolder.livebridge.*` → `com.kakao.taxi.*`.
- Fork features kept: SamsungBridge/Now Bar applier suite, Flashlight suite,
  Notification Capsule, Charging Info, ChatHistoryStore + chat-history
  merging in the Notifier, Reply Proxy (ReplyProxyService/Receiver,
  ReplyInterceptReceiver, proxy intents in `toCompatAction`),
  Messenger/Zalo alert forcing (ALERTS channel, explicit sound/vibration,
  `onlyAlertOnce=false` for chat apps), Wear OS bridging tail in the
  notification builder, VI dictionary + VI strings, DeviceBlocker,
  battery-exemption channels in MainActivity, per-app editor extras
  (icon source, custom notification color).
- `MirrorResult` is a union of both sides (notificationId, mirrorKey,
  removeSource, retryNeeded, retainOriginal) and both `mirroredResult(...)`
  factory overloads are kept.

## Deliberate trade-offs (upstream won, fork code superseded)

- KeepAliveForegroundService: upstream's listener-recovery design replaces
  the fork's heartbeat watchdog (the fork's battery-exemption helpers were
  ported into the upstream file; MainActivity channels keep working).
- NotificationListenerService lifecycle (onPosted/onRemoved/connect/
  disconnect, snapshot sync): upstream's processingHandler + SourceChannel
  lifecycle flow. The fork's lockscreen/charging receivers, flashlight
  hooks, reply-source-cancel helpers and capsule handling were kept and
  re-hooked into that flow.
- Original-notification dismissal: upstream's OriginalRemovalPolicy path;
  the fork's delayed/mirror-verified dismissal chain is still present but
  is no longer the entry path.
- Channel creation: upstream's audible/vibrating channel-variant system
  (`MirrorNotificationChannel.id(audible, vibrating)`); the fork's ALERTS
  channel entry (`livebridge_alerts_v2`) is kept in the enum.
- `dictionary_runtime.dart`: the fork's language-scoped dictionary builder
  was dropped — nothing in the merged tree calls it; upstream's dictionary
  editor/runtime supersedes it. The VI dictionary asset is kept.
- Icon resolution: the fork's `resolveAppIconAssets` stays (its call sites
  use it); upstream's AppMetadataCache is present for its own call sites.
- Network speed: the fork's flat `NetworkSpeedForegroundService` remains
  the active, manifest-declared service. Upstream's `networkspeed/`
  subpackage is included (upstream versions) but is not declared in the
  manifest and its `NetworkSpeedController.sync` call sites were routed to
  the fork's `syncNetworkSpeedService()`.

## Build fix round 1 (after first Windows build attempt)

The merged code reached Kotlin compilation on the maintainer's machine
(the earlier "25.0.3" failure was the machine's Flutter jdk-dir pointing
at JDK 25 — fixed by pointing Flutter at Temurin 17). Round-1 fixes:

- Removed duplicated imports produced by union-resolved import blocks
  (MainActivity, LiveUpdateNotifier).
- Listener: removed the duplicated `activeInstance` companion declaration
  (upstream `private` vs fork `internal` — kept `internal`, which
  ReplyInterceptReceiver/ReplyProxyService use); restored the fork's
  delayed original-dismissal chain (`scheduleOriginalSourceDismissal`,
  `dismissOriginalSourceWhenMirrorActive`) that `retryOriginalSourceDismissal`
  calls; added upstream's key-based self-dismissed set
  (`rememberSelfDismissedSourceKey` / `consumeSelfDismissedSourceKey`)
  alongside the fork's record-based tracking, with
  `forgetSelfDismissedSourceKey` clearing both.
- Notifier: added the missing `isUserDismissedMirror` /
  `isUserDismissedMirrorLocked` helpers (upstream); completed the
  `mirrorChannelText` localization `when` with the fork's
  NOTIFICATION_CAPSULE / CHARGING_INFO / ALERTS branches; restored the
  fork's `toCompatAction(frameworkAction, titleOverride, mirrorKey,
  context)` signature (the union had stitched in a duplicate `context`
  parameter) and converted one upstream positional call site to named
  arguments.
- Restored upstream `networkspeed/NetworkSpeedDataSource.kt` and
  `networkspeed/NetworkSpeedFormatter.kt` — the fork had deleted the whole
  subpackage, so the auto-merge dropped these two files that upstream's
  subpackage service/builder still reference.

## Post-merge work (20/10/2026)

The merged branch now builds successfully on the maintainer's Windows
machine (`app-release.apk`, 66.1MB) and installs and runs on the Z Flip5.

- **Vietnamese localization completed for the App Configuration area:**
  34 strings in `app_strings.dart` that previously fell back to English
  now have `vi:` translations (text filters, source channels, Wear OS
  Live Updates, converted-notification sound/vibration, hide-from-recents,
  network-speed hide-when-locked, promotion status, dictionary editor).
- **Now Bar reply fix:** tapping "Reply" on the Samsung Now Bar fired the
  proxy broadcast with no RemoteInput results (the Now Bar shows no reply
  editor), and the reply pipeline silently dropped the empty reply. The
  reply PendingIntent now targets the new `ReplyInputActivity` directly
  (an activity intent fired from a notification action is exempt from
  background-launch restrictions, whereas a broadcast receiver opening an
  activity gets blocked silently — the first attempt via
  `ReplyProxyReceiver` still showed nothing on device). The activity
  forwards host-provided RemoteInput results straight to
  `ReplyProxyService`, and only shows its small native editor when the
  host provided no reply UI; typed text re-enters the pipeline exactly
  like a host-provided result. `ReplyProxyReceiver` remains for
  already-posted notifications that still carry the old broadcast intent.

## Post-merge fixes (20/10/2026, later)

- **Sound/vibration regression fixed:** upstream's converted-notification
  sound/vibration preferences default to OFF and the publish path forces
  `setSilent(true)` when both are off, which muted every converted
  notification after the merge. Defaults are now ON (the fork's established
  behavior), and the ALERTS channel is always created with the default
  notification sound and the fork's vibration pattern, as in the fork's
  original channel setup.
- **Native inline reply attempt:** phone-facing reply actions now keep
  `showsUserInterface=true` (the flag the fork set to false for Wear), so
  One UI can render its native inline reply editor in the shade / Now Bar
  like a normal notification. Wear-facing action copies (WearableExtender)
  still pass `showsUserInterface=false`, preserving the watch's inline
  keyboard behavior. If the host still shows no editor, the intent fires
  and `ReplyInputActivity` remains the fallback.

## Post-merge fixes (20/10/2026, round 3)

- **Sound/vibration, take 2:** the defaults change alone was not enough on
  the maintainer's device — the ALERTS channel had already been created
  silent by an early merged build (after an uninstall during the version
  downgrade dance), and Android does not let an app change an existing
  channel. The ALERTS channel id is bumped to `livebridge_alerts_v3`
  (created with sound + vibration pattern; v2 is deleted on channel
  ensure), and chat-app mirrors are exempt from the
  converted-notification `setSilent(true)` gate — their alerting is
  governed by the ALERTS channel's own system settings.
- **Reply editor restyled:** the system's inline reply frame (as in the
  Now Bar for normal notifications) is drawn by Samsung's SystemUI and
  cannot be injected into a Live notification by the app. The fallback
  editor is therefore now a compact chat bar anchored to the bottom of
  the screen (rounded card, single-line field + Send), visually matching
  the Now Bar reply frame instead of a large centered dialog.

## Post-merge fixes (20/10/2026, round 4)

- **Watch replies did not dismiss the Now Bar mirror:** the WearableExtender
  action copies carried the source app's raw reply intent, so a reply sent
  from the watch never passed through LiveBridge's interception and the
  mirror stayed up. Wear-facing copies now route through the broadcast
  proxy (`ReplyInterceptReceiver`) with mirror key + context, restoring
  the intercept → local echo → forward → dismiss pipeline for watch
  replies. Phone-facing actions keep the activity PendingIntent +
  `ReplyInputActivity` editor.

## Post-merge change (20/10/2026, round 5): chat mirrors as normal notifications

Per the maintainer's decision: Samsung only renders its own inline reply
frame (Reply field + Send under the card) for NORMAL notifications; Live
Update mirrors only ever fire the action intent, so any in-Now Bar reply
UI would have to be app-drawn, which the maintainer rejected. Chat-app
mirrors (CHAT_APP_PACKAGES) are therefore now posted as normal messaging
notifications: `CATEGORY_MESSAGE`, not ongoing, no promoted-ongoing
request, no Samsung ongoing-activity extras (`SamsungNowBarApplier`
skipped), and `notifyMirroredNotification` no longer marks
`CATEGORY_MESSAGE` notifications Now Bar eligible. All other kinds
(progress, media, calls, OTP, capsule, charging, network speed…) keep
the genuine Now Bar Live treatment unchanged. The reply action keeps the
proxy PendingIntent, so when Samsung's editor delivers the reply,
`ReplyInputActivity` forwards it invisibly and the existing
echo/dismissal pipeline runs; the editor UI remains only as a fallback
for surfaces that still provide no editor.

## Post-merge change (20/10/2026, round 6): round 5 reverted

The maintainer tested round 5 and rejected the trade-off: messaging-app
notifications must become Now Bar again. All round-5 changes are
reverted — chat mirrors are genuine Now Bar Live notifications exactly
like every other kind (ongoing, promoted, Samsung ongoing-activity
extras, Now Bar eligibility marking). The reply editor
(`ReplyInputActivity`, combined card + blue strip unit) remains the
reply UI for surfaces that fire the action intent without their own
editor.

## Post-merge change (21/10/2026, round 7): reply theming, position, APK size

- The reply editor's strip now takes the dominant saturated color of the
  source app's icon (extracted at runtime in `ReplyInputActivity`,
  clamped so white text stays readable), falling back to Samsung blue.
- The combined reply unit sits snug under the status bar (2dp gap).
- APK size: release builds now package arm64-v8a native libraries only
  (`ndk.abiFilters` in `android/app/build.gradle.kts`) — both target
  devices are arm64, so this removes the armeabi-v7a/x86_64 engine and
  plugin libraries without touching any feature. R8/minify remains off
  on purpose (reflection-heavy Samsung bridging); enabling it is a
  separate, test-first experiment.

## Post-merge change (21/10/2026, round 8): reply strip color + position

The maintainer reversed round 7's per-app icon theming after testing:
the reply strip now uses the fixed Samsung Now Bar blue again (matching
the system's own reply frame), and the combined unit sits a few dp
higher (`statusBarHeight - 4dp`). The arm64-only APK size win from
round 7 stands (66.2MB → 34.3MB, verified on device); note the slim
build requires `flutter build apk --release --target-platform
android-arm64` — the Gradle `abiFilters` alone is ignored by Flutter's
packaging.

## Post-merge change (21/10/2026, round 9): reply strip = Now Bar pill color

Clarified by the maintainer with screenshots: the reply strip must wear
the color of the app's COLLAPSED Now Bar pill (TikTok teal, Messenger
blue...), not a fixed blue and not a raw icon average.
`createProxyReplyPendingIntent` now computes the accent with the exact
precedence `mirrorNotification` uses for the mirror itself (per-app
custom color from `AppPresentationOverridesLoader`, else
`SamsungLiveUpdateReparser.resolveChipBackgroundColor(source)`) and
passes it to `ReplyInputActivity` as `EXTRA_REPLY_ACCENT_COLOR`; the
strip renders it opaque, falling back to Samsung blue only when no
accent was provided.

## Known issues / verify on device

- **Kotlin/Android was NOT compiled.** This environment cannot run Gradle.
  Dart side is verified (below); the Android build must be done on the
  maintainer's Windows machine. Expect a small number of Kotlin compile
  errors to shake out at first build — the merge was brace-balance checked
  for every Kotlin file, but that is not a type check.
- `flutter analyze`: **No issues found.**
- `flutter test`: **42 passed, 2 failed.** The 2 failures are
  `permissions_and_editor_test.dart` "scrolls to the last option" (default
  + per-app): the sheet now stops 2.2px short of maxScrollExtent after the
  test's single drag. On pure upstream these pass; the fork's editor sheet
  content is taller (extra icon-source / notification-color sections), so
  one drag no longer settles exactly at the bottom. Cosmetic/test-physics
  only — the sheet scrolls fully by hand.
- Test the fork's headline flows after building: Messenger + Zalo sound/
  vibration, Wear OS chat history + inline reply, Samsung Now Bar on a
  Galaxy device, flashlight mirror, network-speed pill.
- `android/build/reports/problems-report.html` (a committed Gradle build
  artifact) was removed in this merge.
- 4 upstream promo screenshots under `output/promo/` (watch-*.png) are not
  in this branch — they are binary marketing assets that could not be
  pushed through the API used for this merge; they exist in upstream.
- `.github/workflows/android.yml` (upstream's CI workflow) is also not in
  this branch: the API integration used for the push lacks the workflows
  permission (403). To enable GitHub Actions builds of this branch, add
  that file manually via the GitHub web UI (copy it from
  appsfolder/livebridge). Everything else in the branch tree is identical
  to the locally verified merge tree.

## Network-speed Now Bar intermittency fix (round 28, 2026-10-21)

Owner report: with regular-notification mode OFF, the network-speed
notification still SOMETIMES appeared as a plain notification instead of
the Now Bar capsule. Two mechanisms in the tick loop caused the flap:
(1) NetworkSpeedNotificationBuilder recomputed promotion from the single
current sample on every rebuild (1.5s), so one below-threshold/transient
sample rebuilt the notification without the Samsung extras and the
capsule dropped out until the next healthy tick; (2)
NetworkSpeedForegroundService.refreshServiceState stopped the service —
cancelling the notification — on ANY build/post failure, so one
transient error removed the feature until a later sync restarted it.

Fix: promotion now LATCHES in the builder (one instance per service):
the first sample meeting the threshold promotes, and promotion holds
until regular-notification mode is enabled or the service stops; the
threshold only gates the first appearance. refreshServiceState no longer
self-stops on build failures (keeps the posted notification) or on
update-post failures (retries next tick); only a failed initial
startForeground still stops the service (system watchdog parity).
Static checks only (brace balance 0/0 on both files); Windows build +
on-device verification by the owner pending.

## VPN / external-device Now Bar stickiness (round 29, 2026-10-21)

Same intermittency class as round 28, on the source-conversion side:
detectSmartStage re-runs text detection on EVERY update of a VPN or
external-device notification. The vpn rule requires a speed pattern in
the CURRENT text — a transient speed-less update ("Connecting...", a
status line without figures) failed the gate and then hit
shouldSuppressNonTrafficVpn, which CANCELLED the mirror outright; the
external_device rule similarly requires the device name in the current
text, and a miss downgraded the mirror to the plain form. The capsule
flapped in and out of the Now Bar on ordinary status updates.

Fix (sticky classification): a new latchedSmartMatchesBySbnKey map
(stateLock-guarded). When the smart branch publishes a vpn or
external_device match it latches the match for that sbn.key; when a
later update of the same key detects nothing, maybeMirror reuses the
latched match (helper latchedSmartMatchFor: master smart switch +
per-rule toggle still on + aggregate package matches the source
package). The smart branch already degrades content gracefully
(formatDominantVpnTrafficText / extractExternalDeviceStatusText fall
back to the default status text), so only fresh DATA is lost on a
missed update, never the form; a non-null smartMatch also makes the
no-traffic suppression unreachable for a proven-traffic capsule. The
latch is cleared inside clearSmartTrackingForSbnKeyLocked, the funnel
for source removal and kind changes. Other smart rules (taxi, food,
navigation, weather) are intentionally NOT latched — their stage
semantics are progression-based, not connection-based.
Static checks only (brace/paren balance verified); Windows build +
on-device verification by the owner pending.

## TEMP diagnostics for network-speed promotion (round 30, 2026-10-21)

Owner's Flip5 (One UI 9) on the round-28/29 build: the network-speed
notification posts and updates but stays in the plain "other
notifications" form, although it rendered as a Now Bar capsule on the
pre-round-28 build at even lower speeds. Static re-check of the whole
transition path found NO upgrade step anywhere: the form is decided at
build/post time (mirrors: notifyWithPromotionFallback posts the
promoted form first and falls back only when posting throws; network
speed: rebuilt every 1.5s with the current latch state). With
regular=off + threshold=0 the first build should already be promoted,
so a live input must differ from what the code expects — candidates:
the stored regular/threshold values, or the hidden-API semFlags write
in SamsungOneUi7NowBarCompat being denied at runtime (it is wrapped in
runCatching, so a denial is invisible).

TEMP: NetworkSpeedNotificationBuilder now logs (tag NetSpeedDiag,
change-only) regular/threshold/total/latched/promote/samsung plus a
read-back of the semFlags ongoing bit after markEligible, the system's
canPostPromotedNotifications state, and the channel importance.
REMOVE this logging once the owner's logcat identifies the branch.

## Round 30 CLOSED — diagnosis result (2026-10-21)

The owner's NetSpeedDiag logcat settled it: NO code bug. At the time of
the "still not a Now Bar" screenshot the stored prefs were
regular=true (22:34 block, speeds up to 12,269 B/s, promote=false);
after he switched regular off, threshold=73728 (72 KB/s = nine 8 KB/s
UI steps) kept every ordinary tick (60–2,000 B/s) below the gate
(22:35:57–22:37:11, latched=false). The moment real traffic exceeded
the threshold (2,260,285 B/s at 22:37:13) the log shows
latched=true promote=true samsung=true semOngoing=true — the capsule
promoted and the round-28 latch held it. The whole "sometimes not a
Now Bar" history was the 72 KB/s threshold vs bursty traffic.
canPostPromotedNotifications=true and channelImp=2 throughout.
TEMP diagnostics removed in this commit; the threshold local-val
refactor stays (behavior identical). Owner action (no code): set the
network-speed threshold back to 0 for an always-on capsule.

## Network speed: threshold no longer gates the Now Bar capsule (round 31, 2026-10-21)

Owner decision after the round-30 diagnosis: on first run the capsule
still did not appear until he dragged the "minimum speed to display"
slider — i.e. the stored 72 KB/s threshold gated the FIRST appearance
(the round-28 latch only kept the capsule up after a first crossing).
His product rule for the fork: turning on "Show network speed in
Now Bar" (the feature's master toggle) must be sufficient. Change:
NetworkSpeedNotificationBuilder drops the latch and the threshold from
the promotion decision entirely — shouldPromote is now simply
!regularNotificationOnly, so the capsule promotes from the first frame
whenever the feature is on and regular-only mode is off. The threshold
preference remains stored (settings backup/restore compatibility) and
the settings slider still displays it, but it no longer affects the
capsule; removing the slider from the UI is a separate decision left
to the owner. This intentionally diverges from upstream behavior.
Static checks only (brace/paren balance 0/0, latch references gone);
Windows build + on-device verification by the owner pending.

## VPN / external devices: feature toggle is sufficient (round 32, 2026-10-21)

Same owner product rule as round 31, applied to the two remaining
Network & connections features. Before: a VPN status notification
converted only while its text carried parseable speed figures (a
plain "Connected" status never converted and was actively cancelled
by the no-traffic suppression); an external-device notification
converted only when the device name could be extracted from the
current text. Now detectSmartStage has two context fallbacks after
the rich detectors: detectVpnContextSmartStage (known VPN package, or
VPN context on an ongoing status notification — the ongoing
requirement keeps promos out for unknown packages) and
detectExternalDeviceContextSmartStage (curated external-device
package identity alone; the debugging-ignore setting still applies).
Both reuse the existing aggregate keys and stage shape, so the smart
branch publishes them like any smart match and its content extraction
degrades gracefully (default status text until real speeds/device
data can be parsed). Combined with the round-29 latch, first
conversion no longer depends on fragile text content and later
updates can no longer flap the form. Static checks only (brace
balance 0; file paren baseline unchanged); Windows build + on-device
verification by the owner pending.

## VPN / external devices: the real blocker was the ongoing core filter (round 33, 2026-10-21)

Owner test of round 32: with 1.1.1.1 (WARP) connected and a Galaxy
Watch FE connected, neither appeared as a Live Update. Root cause
found one level above detection: maybeMirror runs passesCoreFilters
first, and that filter rejects EVERY ongoing notification
(FLAG_ONGOING_EVENT + isOngoing, and non-clearable + isOngoing) for
non-Zalo packages. VPN status and device-connection notifications are
ongoing by nature, so they were discarded before detection ever ran —
rounds 29/32 improved a stage these sources never reached (the same
filter also explains the A15 reporter's Play Store complaint:
download progress notifications are ongoing too; fixing THAT path is
not in this round's scope).

Fix, deliberately narrow: (1) passesCoreFilters gains
allowOngoingSmartCandidate, which waives ONLY the two ongoing checks.
(2) maybeMirror computes isOngoingSmartStatusCandidate (ongoing +
smart master switch on + the feature's toggle on + likely VPN package
or VPN-context text, resp. curated external-device package). All
other core checks (own package, group summary, local-only, blocked
sources, channel/text filters) still apply. (3) Scoped guard: a
notification admitted via the exemption that produces no smart match
is NOT mirrored generically — it is cancelled/ignored as before, so
the exemption cannot leak into chat/progress mirroring.
(4) Dictionary coverage: "cloudflare" added to vpn_package_markers
and the vpn rule's package_hints in all 7 dictionary files (WARP's
package com.cloudflare.onedotonedotonedotone matched no marker and
its notification text carries neither "vpn" nor speeds); "watchmanager"
added to the external_device package_hints (the Galaxy Wearable app
package contains neither "wearable" nor "galaxywearable"). All files
re-validated as JSON. Static checks only (brace 0, paren baseline
unchanged); Windows build + on-device verification by the owner
pending — if the watch still does not convert, the deciding datum is
the posting package of its connection notification (dumpsys).

## VPN/external context fallbacks: package identity is enough (round 34, 2026-10-21)

Owner device timeline: 1.1.1.1 notifications were system-blocked
(importance=NONE in dumps 1-3); after he enabled them, the WARP status
notification exists (channel VpnService, ONGOING_EVENT|NO_CLEAR|
FOREGROUND_SERVICE, posted 23:49) and the round-33 build is installed,
yet no VPN capsule appeared. Remaining suspect in the round-32
fallbacks themselves: both still required NON-BLANK collected text
before matching, so a (nearly) text-less VPN/device status post could
never match even from a curated package — contradicting the stated
"package identity alone" design. Fix: detectVpnContextSmartStage now
returns a match immediately for a likely VPN package; the
text+context+ongoing requirements remain for unknown packages.
detectExternalDeviceContextSmartStage likewise no longer blocks on
blank text (the debugging-ignore check only runs when text exists).
If the capsule still does not appear on the next build, the remaining
unknowns are runtime-side (listener bind timing at post time, the
notification's actual extras) and the next datum is a full dumpsys.
Static checks only (brace 0, paren baseline unchanged).

## VPN capsule live traffic + VpnDiag trace (round 35, 2026-10-22)

Owner's point: WARP's own notification prints no speeds, so a
converted capsule would show status only. Round 35: (1) when the
source prints no speeds, the VPN capsule falls back to self-measured
rates (TrafficStats total deltas — while a VPN is on, device totals
are the VPN throughput), formatted like parsed tokens; (2) the
notifier tracks the active VPN mirror's source key and the network
foreground service re-drives its mirror on the existing 1.5s tick
(refreshActiveVpnMirror, 1.2s throttle, validates source still active
+ features still on) so the rates stay live. TEMP VpnDiag logcat
points trace the cloudflare source through maybeMirror (candidate,
smartMatch, live dictionary stats, suppression/guard blocks, publish)
— the round-34 build produced no capsule on the owner's phone and
static audit exhausted the gates (one suspect: the parser dictionary
merges only ENABLED language packs over an EMPTY default, and "vi" is
not in SUPPORTED_PARSER_DICTIONARY_LANGUAGE_IDS, so a customized
enabled set would silently empty the dictionary). Remove the VpnDiag
points once the on-device cause is read. Static checks only.

## VpnDiag layer trace extended (round 36, 2026-10-22)

The round-35 build produced NO VpnDiag output at all on the owner's
phone after a fresh WARP toggle, so the trace is extended to every
layer: listener POSTED, maybeMirror ENTRY, CHANNEL-BLOCKED,
CORE-FILTERED (with the candidate value), BASE-FILTERED — combined
with round 35's candidate/smart/dictionary line, SUPPRESSED, ongoing
GUARD and PUBLISH points, one test run now identifies the exact layer
that stops the WARP source. All points are TEMP and come out together
once the cause is read. Test protocol for the owner: flutter clean +
full build (rules out any stale-APK doubt), install, open the app,
toggle WARP off/on, read logcat -s VpnDiag.

## ROOT CAUSE FOUND: the per-app allowlist vetoed the VPN feature (round 37, 2026-10-22)

The round-36 layer trace on the owner's phone was unambiguous: every
WARP post reached maybeMirror (ENTRY) and died at BASE-FILTERED.
passesBaseFilters has three vetoes — media misclassification (ruled
out: no transport category / media session / MediaStyle), dictionary
blocked packages (ruled out: no cloudflare entry anywhere) — leaving
prefs.isPackageAllowed: the owner's package mode is "selected apps"
and 1.1.1.1 was never added, so the global Smart VPN toggle was
silently vetoed by an unrelated settings area. Rounds 29-34 were all
correct but unreachable: the flow order is base filters BEFORE smart
detection, so no detection fix could ever take effect for an
unlisted app (the same wall would have stopped the watch source).
Fix per the owner's standing rule for this feature group (a toggle
must be sufficient): admitted ongoing smart-status candidates (VPN /
external device, round 33) now skip ONLY the per-app allowlist term
of passesBaseFilters (new skipPackageAllowlist parameter, fed by
ongoingSmartCandidate); the media and blocked-source vetoes still
apply, and the ongoing guard still keeps candidates out of the
generic mirror. All TEMP VpnDiag points (rounds 35-36) removed; the
round-35 self-measured live traffic for the VPN capsule stays.
Static checks only (brace 0, paren baseline unchanged, no VpnDiag
references remain).

## VPN capsule confirmed; live refresh fixed + source replaced (round 38, 2026-10-22)

Owner confirmed the VPN capsule appears on the round-37 build. Two
follow-ups from his test: (1) The round-35 rate refresh never worked
— refreshActiveVpnMirror looked the source up via
NotificationManager.activeNotifications, which returns only THIS
app's own notifications, so the first tick found nothing and cleared
the tracking key. Fix: the notifier now keeps the source SBN itself
(activeVpnMirrorSourceSbn) and re-drives maybeMirror with it.
(2) The capsule sat next to WARP's own plain notification. Upstream's
removal policy hard-blocks dismissing persistent sources
(OriginalRemovalPolicy.mayRemove returns false for persistent, and
maybeDismissOriginalSource required isClearable), which is why
originals of ongoing sources were never hidden. The VPN feature is a
replacement by design, so smart VPN mirrors now return
removeSource=true and the listener's dismissal honors that request
over the clearable check and the removal policy (self-dismiss
bookkeeping + mirror retention unchanged, so the mirror survives the
source's removal and the round-35 refresh keeps its rates live from
the stored snapshot). If WARP re-posts on state changes, each repost
is mirrored and dismissed again. Static checks only.

## VPN source dismissal: snooze fallback (round 39, 2026-10-22)

Round-38 dismissal executed without error but the WARP original
stayed: dumpsys proved it was posted once and never reposted, and
logcat showed no failure and no removal event — One UI silently
ignores listener cancels of this foreground-service notification.
Fallback in maybeDismissOriginalSource for removeSource (VPN)
results: 1.2s after the cancel attempt, if the source key is still
in the listener's active notifications, snoozeNotification hides it
for 12h (the app cancelling its own notification on disconnect
clears the snooze with it). TEMP VpnDismiss log line confirms which
mechanism took effect; remove after on-device confirmation.
Static checks only.

## VPN source snooze confirmed; temp trace removed (round 40, 2026-10-22)

On-device log confirmed the round-39 fallback firing exactly as
designed ("cancel ineffective; snoozed 0|com.cloudflare...|101"):
One UI ignores the listener cancel for the WARP foreground-service
notification, the verification pass finds it still active, and the
snooze hides it — the shade now shows only the VPN capsule, which
keeps updating with self-measured traffic on the 1.5s tick. The
TEMP VpnDismiss trace line is removed; the mechanism stays. The
Network & connections trio (speed / VPN / external devices) is now
functionally complete on the owner's device, with the external-
device capsule still awaiting a source that actually posts a
notification (the Galaxy Watch FE posts none on One UI 9).

## Snoozed VPN source must stay visible to sync/reconcile (round 41, 2026-10-22)

Regression from round 39, found by the owner minutes after the
cleanup build: the VPN capsule vanished entirely. Cause: a snoozed
source is absent from the listener's active list, so the periodic
snapshot sync / reconnect reconcile treated it as removed and
cancelled the capsule — while the original stayed snoozed and
hidden, leaving no VPN UI at all. Fix: the two snapshot reads that
drive reconcile and (re)processing now use sourceSnapshots() =
active + snoozed notifications that still qualify as ongoing
smart-status sources (new LiveUpdateNotifier.isSmartStatusSource
wrapper over the round-33 candidate check; the candidate filter
keeps user-snoozed unrelated notifications out of the pipeline).
Dismissal is also skipped for an already-snoozed source (a cancel
against the snoozed record could delete it and orphan the mirror).
Disconnect still cleans up: the app cancelling its notification
removes the snoozed record, the removal event fires, and the mirror
is cancelled as before. Static checks only.

## Vietnamese added to the native locale system (round 42, 2026-10-23)

Owner: the charging capsule showed only English. Root cause: the
notifier's AppLocale enum (which drives every native string table)
covered EN/RU/TR/PT_BR/ZH/KO but had no VI at all, so a Vietnamese
app/device setting silently fell back to EN even though the Dart UI
is fully translated. Added AppLocale.VI end to end: enum value, both
mappings (app language tag + device locale), and a Vietnamese branch
in all 12 locale whens — the charging set ("Đang sạc", "Sạc nhanh",
"Sạc siêu nhanh", "Sạc siêu nhanh 2.0", "Đã sạc đầy", "Còn X phút
nữa là đầy", charging channel name/description) plus the capsule-
frame set the enum forces to stay exhaustive (capsule title "X
thông báo", capsule channel, "Xóa", "Hệ thống"). The low-battery
fallback title got a context parameter threaded through so it can
localize too ("Pin yếu X%"; other locales keep the old text there).
Verified statically: every when(appLocale) block carries a VI branch
(or an else), brace balance 0, paren baseline unchanged.

## Charging remaining time: hours + minutes, VI wording (round 43, 2026-10-23)

Owner refinements after round 42 tested well: (1) the Vietnamese
until-full wording changes from "là đầy" to "sẽ đầy"; (2) estimated
times of an hour or more now render as hours + minutes in every
locale (70 -> "Còn 1 tiếng 10 phút nữa sẽ đầy" / "1 h 10 min until
full"), with an hours-only form when minutes are 0; sub-hour values
keep each locale's previous minutes-only phrasing verbatim. The
low-battery side already mirrors the device's own system
notification text, so it needed no wording work. Static checks only.

## Charging estimate syncs with the system (round 44, 2026-10-23)

Owner observed the capsule's remaining minutes sat +1 against the
system's own charging estimate. Two causes, both fixed:
(1) remainingChargingMinutes rounded UP everywhere (millis and all
seconds fallbacks now round to the nearest minute, keeping the
at-least-1-minute floor); (2) the capsule only recomputed on
battery broadcasts, so between events the system's live number
moved on while the capsule's stood still — refreshChargingInfo now
arms a generation-guarded 30-second self-tick (same pattern as the
appearance delay) after every successful charging post; the tick
re-runs the refresh and re-arms, and stops on low-battery posts,
cancellation, or unplug. Static checks only.

## Charging capsule allowed on the standby screen (round 45, 2026-10-23)

Owner: the charging capsule never appeared on the standby (AOD)
Now Bar although the other capsules do. The charging builder was
the only self-posted capsule hardwired lockscreenOnly = true,
which the Samsung bridge renders as STYLE_NOW_BAR_ONLY; smart
mirrors only use that style when their per-feature lockscreen-only
setting is on (weather/VPN), everything else takes STYLE_DEFAULT.
The charging capsule now uses the default style too, so it is
eligible on the standby surface like the rest. The notification-
counter capsule keeps its hardwired lockscreen-only style (not
requested). Static checks only; on-device standby check pending.

## Standby chip shows time until full, not the percentage (round 46, 2026-10-23)

User feedback relayed by the owner: on the standby screen the
charging capsule's percentage is the least useful glanceable value;
the time until full is what people want there. The capsule's
condensed text (bridge chipText + the promoted short critical text,
which is what the standby/status-bar chip renders) now carries the
remaining-time text ("Còn X phút nữa sẽ đầy"; the charging label
when no estimate exists, "Đã sạc đầy" when full) instead of the
percentage. The lock-screen pill and the shade are unchanged: the
percentage stays as the title with the time underneath. Low battery
keeps its expanded-text chip. Static checks only.

## Standby chip: compact "≈ HH:mm" remaining time (round 47, 2026-10-23)

Owner relayed that the round-46 full sentence is too long for the
standby chip and gets cut off. The chip (bridge chipText + promoted
short critical text) now uses a compact numeric form built from the
raw estimate, which the charging snapshot now carries
(remainingMinutes, null when there is no estimate or the battery is
full): "≈ 01:05" for 65 minutes, "≈ 00:45" under an hour. A zero
estimate shows a localized "almost full" status (VI "Sắp đầy")
instead of "≈ 00:00". No-estimate and full states keep the previous
chip text ("Đang sạc" / "Đã sạc đầy"); the lock-screen pill, the
shade and low battery are untouched. Static checks only.

## System charging notification hidden while the capsule is on (round 48, 2026-10-23)

Owner: with the charging capsule present, the system's own
"Đang sạc" entry still sat in the shade under Other notifications —
the same duplication the VPN work removed. Identity verified from
his dumpsys capture: SystemUI, tag "charging_state", channel CHR,
importance MIN, and the channel is not user-blockable
(mBlockableSystem=false), so settings cannot hide it. While the
converter + charging-capsule feature is on, the listener now
snoozes that record on sight (12h, the round-39 pattern) — both in
the posted pipeline and in the charging-monitoring sync, so
toggling the feature on mid-charge hides an already-posted one.
Identity lives in LiveUpdateNotifier.isSystemChargingNotification
(tag-based, language-independent). Known limit: no un-snooze API,
so switching the feature off mid-charge restores the system's
entry at the latest on the next plug-in; unplugging kills the
record and its snooze as usual. Static checks only.

## System charging notification restored when the capsule is off (round 49, 2026-10-23)

Owner's rule, completing round 48: hiding the system's charging
notification applies only while the charging capsule is enabled;
switching the capsule off must bring the system's entry back.
Android has no un-snooze API, so the disabled branch of
syncChargingInfoMonitoring (which both MainActivity toggle paths
reach via requestChargingInfoSync) now scans the snoozed records
for the SystemUI charging_state one and re-snoozes it with a
1-second duration — the system hands it back to the shade
effectively immediately. No-op when nothing is snoozed. This
replaces round 48's "returns at the next plug-in" limitation.
Static checks only.

## "Fully charged" spelled out in every locale (round 50, 2026-10-23)

Owner: the full-charge state read complete in Vietnamese ("Đã sạc
đầy") but clipped in English ("Full"). Audited the whole table and
completed every clipped entry to the full meaning: EN "Fully
charged", RU "Полностью заряжено", TR "Tamamen şarj oldu", PT_BR
"Totalmente carregado", KO "완전히 충전됨". VI and ZH (简/繁) were
already complete and are unchanged. Single source:
chargingInfoFullText feeds the collapsed text, the expanded text
and the standby chip's full state. Static checks only.

## Chip duration no longer looks like a clock time (round 51, 2026-10-23)

Owner caught a real misreading in round 47's "≈ HH:mm" chip: at
03:30 a chip reading "≈ 03:37" (a 3h37 duration) sits next to the
standby clock and reads as the wall-clock time 03:37 — "full in a
few minutes". The chip now renders each locale's compact unit form
instead: EN "≈ 3h 37m" / "≈ 45m", VI "≈ 3h 37p" / "≈ 45p", RU
"≈ 3ч 37мин", TR "≈ 3 sa 37 dk", PT_BR "≈ 3h 37min", ZH "≈ 3小时
37分钟", KO "≈ 3시간 37분"; hours-only and minutes-only cases drop
the empty part. The zero-estimate "almost full" status and all
other surfaces are unchanged. Static checks only.

## Tapping the charging capsule opens Settings > Battery (round 52, 2026-10-23)

Owner: tapping the charging capsule should open the phone's
Battery settings page, not LiveBridge. The charging builder now
uses a dedicated content intent: ACTION_POWER_USAGE_SUMMARY (the
battery page on One UI and most OEM ROMs), falling back to
ACTION_BATTERY_SAVER_SETTINGS, then to the previous open-the-app
intent so the tap never dead-ends. Other capsules keep opening the
app via the shared helper, which is untouched. Note: his J7 Prime
(S22 Ultra port ROM) needs `adb install --no-streaming` — its old
adbd rejects streamed installs with a splice EINVAL. Static checks
only.

## Capsule tap targets the main Settings > Battery page (round 53, 2026-10-23)

Round 52's POWER_USAGE_SUMMARY landed on the battery *activity*
sub-page; the owner wants the parent Settings > Battery page.
His activity dump (J7 Prime, S22 Ultra port) shows that page is
Device Care's com.samsung.android.lool/
com.samsung.android.sm.battery.ui.BatteryActivity, launched from
the Settings root list. The content intent now prefers that
explicit component, guarded by a resolve + exported check (a
non-exported system activity would silently refuse a third-party
tap); fallbacks unchanged: POWER_USAGE_SUMMARY, battery saver,
then the app. Static checks only.

## Build fix: duplicate ComponentName import (round 54, 2026-10-23)

Round 53 added `import android.content.ComponentName`, but the
file already imported it (the import block is not sorted, so the
anchor missed it) — Kotlin rejected the build with "Conflicting
import". The duplicate line is removed; no behavior change. Note
the owner's install right after the failed build silently pushed
the stale round-52 APK, which is why the capsule tap still opened
the battery activity page in his check.

## Flashlight source duplication: fallback snooze was 1.5 seconds (round 55, 2026-10-23)

User report (pt-BR device): with the flashlight capsule on, the
system's own "Lanterna" notification stayed in the shade under it.
The dismissal chain tries cancelNotification + cancelNotifications
first, but on One UI builds that silently ignore listener cancels
(the round-39 finding) the only fallback was a 1.5-second snooze —
and the 300ms verify even saw the source "gone" during that
window, so nothing ever re-hid it when it popped back ~1.5s
later. The fallback snooze is now 12 hours, matching the VPN and
system-charging sources (it dies with the record when the torch
turns off). Added the round-49 counterpart too: switching the
flashlight feature off restores the system's notification by
re-snoozing it for 1 second. Static checks only.

## Flashlight capsule fully localized (round 56, 2026-10-23)

Owner: apply all app languages to the flashlight capsule. It was
the last binary holdout — builder and foreground service switched
strings on a device-locale Russian check, everything else saw
English. New FlashlightStrings table (same rules as the charging
capsule: app language setting first, device locale fallback;
EN/RU/TR/PT_BR/ZH_HANS/ZH_HANT/KO/VI) covers the title, chip text,
turn-off action, both secondary texts, both warning texts, and
the channel name + description; the service re-applies the channel
name on each sync, so language switches rename it live. Upstream's
Russian strings kept verbatim. The duplication fix from round 55
is NOT confirmed on the owner's devices (he reports the latest
build still duplicates on Flip5 and J7 Prime) — reopened, pending
a torch-on dumpsys capture to compare the SystemUI record's real
tag/channel against the identity constants. Static checks only.

## Flashlight duplication root-caused and fixed (round 57, 2026-10-23)

Owner's torch-on logcat settled it: every dismissal attempt logged
"Skip flashlight source dismiss: no tracked key". The tracked key
is written only by syncFlashlightMirror — and that function had NO
call sites left after the 2.2.1 hand-merge (the foreground service
is torch-callback driven, so the capsule worked; the snapshot-side
tracking that feeds dismissal was orphaned). Identity was never
the problem (dump: SystemUI id 4660, tag Flashlight, channel
FLASHLIGHT_ONGOING — exact match), and the 12h snooze from round
55 simply never ran. Fix: call syncFlashlightMirror(snapshots)
from the periodic snapshot sync (4s cadence, alongside
reconcileSources) and from the onListenerConnected snapshot pass.
Each pass that sees the source re-arms the key and re-triggers
the service sync, so dismissal now runs and self-heals. Static
checks only; device verification pending.

## Flashlight source hidden at post time (round 58, 2026-10-23)

Owner asked for the ~1.5s of visible SystemUI flashlight card
after torch-on to go away. The round 57 chain (snapshot sync ->
tracked key -> service dismissal) is correct but inherently
laggy; the charging capsule already proves the fast pattern on
this device. maybeSnoozeSystemFlashlightNotification now runs in
onNotificationPosted next to maybeSnoozeSystemChargingNotification:
on sight of the SystemUI flashlight record (converter + smart
flashlight on, supported device) it remembers the key, marks it
self-dismissed (so the removal handler does not read our snooze
as a torch-off and tear the capsule down), and snoozes it for 12h
immediately. The dismissal machinery remains as backstop for
records posted before the listener connected. Literal zero delay
is impossible for any listener app — the notification must exist
before it can be snoozed — but the snooze now lands in the posted
event itself. Static checks only; device verification pending.

## Self-estimated time-to-full while the system has none (round 59, 2026-10-23)

Owner's J7 Prime (port ROM) takes 2+ minutes after a replug
before the system produces a charge-time estimate again; the
capsule sat on the bare "charging" text meanwhile (the app polls
every 30s and adopts the system number the moment it exists —
the data itself was late). remainingChargingMinutes gains a
last-resort fallback, tried only after computeChargeTimeRemaining
and all Samsung extras come back empty: estimate from the
battery's charge counter + flow. A counter-delta rate over
samples (window 5 min, min span 20s, reset when the counter
drops) is preferred; the instantaneous current is the immediate
fallback (accepted only within 50mA..12A). Sanity-bounded to
1..1440 minutes. System estimates keep priority. Static checks
only; device verification pending.

## Percent-rate estimate for counter-less devices (round 60, 2026-10-23)

The round 59 property estimate cannot fire on the owner's J7
Prime: the device has no /sys/class/power_supply/battery/
charge_counter at all (owner-verified via adb), so the counter
gate returns null and the capsule again waited on the system's
2+ minute calibration after a replug. Final fallback added:
estimate from the observed percent-per-minute rate (percent is
always available). Samples are taken on every charging refresh;
a rate needs >=1% gained over >=45s. The learned rate is kept
for 30 minutes across an unplug (samples themselves are cleared
on cancel), so a quick unplug/replug shows an estimate
immediately from the pre-unplug rate. Priority order is now:
system estimate -> Samsung extras -> charge counter/current ->
percent rate. Static checks only; device verification pending.

## In-app sound/vibration OFF now silences chat mirrors (round 61, 2026-10-23)

Reporter: notification sound is duplicated / still plays even
with sound and vibration disabled inside the app. Code audit:
chat mirrors post to the ALERTS channel (created always-audible
by design), the setSilent gate explicitly EXEMPTED that channel,
and the Zalo / Messenger Wear-bridging paths called
setSilent(false) + forced sound/vibration AFTER the gate,
undoing it entirely. So for chat — the flagship case — the
in-app toggles had no effect at all, and every mirror update
re-alerted on top of the source app's own sound. Fix: the
both-off gate now applies to every channel, and both bridging
paths only force alerting when at least one of the two
preferences is on (otherwise they post silent). Defaults are
unchanged: with the preferences on, chat alerting behaves
exactly as before and the ALERTS channel's system settings
still govern it. Static checks only; device verification
pending.

## Download progress notifications can mirror now (round 62, 2026-10-23)

Owner asked for Play Store-style download progress bars. Audit:
the whole downstream pipeline already existed — a dedicated
hasNativeProgress branch building PROGRESS_NOTIFICATIONS mirrors
with Samsung progress — but passesCoreFilters rejected every
ongoing and every non-clearable+ongoing notification, and
download notifications (Play Store above all) are both for their
whole lifetime, so none ever reached the branch (same root as
the unclosed Galaxy A15 "Play Store aren't pushed" report).
Fix: isOngoingProgressCandidate (ongoing + real progress extras
+ not a group summary) earns the same ongoing exemption the
smart status candidates use, in both the ongoing-event and the
non-clearable checks. The package allowlist still applies, and
the summary notification itself stays rejected — the per-app
children carry the progress. Static checks only; device
verification pending (download an app from Play Store and watch
the Now Bar).

## Progress mirror replaces its source (round 63, 2026-10-23)

Owner verified round 62 (Play Store download capsule appears)
but the original Play Store download notification stayed in the
shade next to it. The progress branch now returns
removeSource = true, reusing the VPN-proven dismissal: cancel,
verify after 1.2s, snooze 12h if One UI keeps the record. A
snoozed source stops emitting posted events, so — the round 41
lesson — snoozed progress records are folded into the listener's
snapshot sync via the new LiveUpdateNotifier
.isProgressSourceNotification check; their changed postTimes
re-drive the mirror at the sync cadence, so the percentage keeps
moving while the original stays hidden. On completion the
store cancels its record (snooze dies with it) and the mirror
resolves through the usual reconcile. Static checks only;
device verification pending.

## Progress mirror dies with its source (round 64, 2026-10-23)

Owner: pressing Cancel on the progress capsule must cancel the
download AND remove the capsule at once. The action side already
worked (mirror actions carry the store's own intent, so the
download really cancels and the store removes its record), but
the removal handler only refreshed counters and left the mirror
to the <=4s snapshot reconcile. Fast path in
handleNotificationRemoved: a progress source whose record is no
longer in the snoozed list (the store truly cancelled it) now
cancels its mirror immediately. The echo of our own round-63
snooze still finds the record snoozed and falls through to the
existing self-dismissed guard, so hiding the source never kills
the mirror. Static checks only; device verification pending.

## Progress capsule can no longer outlive its download (round 65, 2026-10-23)

Owner: after pressing Cancel (the download really cancels in
the Play Store) the progress capsule stayed, and tapping it did
nothing. Root cause: round 63's dismissal parks the source key
in the lifecycle's intentionally-removed set with no expiry,
and the store deleting its SNOOZED record delivers no removal
event to this listener — so neither the round 64 fast path nor
the snapshot reconcile could ever retire the mirror (and the
system cancels the dead record's pending intents, which is why
the zombie's tap was inert too). Progress mirrors have no
driver besides their source record, so their retention now
lapses: reconcileSources releases it after the key is absent
from both the active and snoozed snapshot lists for two
consecutive cycles (~8s), letting the existing missing-key path
cancel the mirror. Round 64's instant path still applies when
a removal event does arrive. Static checks only; device
verification pending.
