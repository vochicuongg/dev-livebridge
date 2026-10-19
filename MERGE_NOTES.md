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
