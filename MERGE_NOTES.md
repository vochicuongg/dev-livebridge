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
