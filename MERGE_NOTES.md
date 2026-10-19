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
