# Round 149 (APK size): keep rules for the R8 release build.
# The app's reflection targets are Android framework classes only
# (Notification.semFlags, RemoteViews.mActions, SystemProperties,
# StatusBarManager, ...), never app classes, so R8 can shrink and
# obfuscate the app code freely. These keeps cover the entry points
# the system instantiates by class name (the manifest components are
# kept by R8 already; listed here explicitly so a future AGP/R8
# change cannot drop them) and the Flutter embedding.
-keep class com.kakao.taxi.MainActivity { *; }
-keep class com.kakao.taxi.liveupdate.LiveUpdateNotificationListenerService { *; }
-keep class com.kakao.taxi.liveupdate.KeepAliveForegroundService { *; }
-keep class com.kakao.taxi.liveupdate.NetworkSpeedForegroundService { *; }
-keep class com.kakao.taxi.liveupdate.FlashlightForegroundService { *; }
-keep class com.kakao.taxi.liveupdate.LiveBridgeTileService { *; }
-keep class com.kakao.taxi.liveupdate.ReplyInputActivity { *; }
-keep class com.kakao.taxi.liveupdate.ReplyProxyService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }
-keep class * extends android.service.quicksettings.TileService { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
# Flutter embedding (also covered by the Flutter plugin's own rules).
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.** { *; }
# HyperIsland kit (Xiaomi) — kept so R8 does not strip its builders.
-keep class io.github.d4viddf.hyperisland_kit.** { *; }
# Round 150: the Flutter embedding references Play Core (Play Store
# split install / deferred components) in FlutterPlayStoreSplit-
# Application and PlayStoreDeferredComponentManager, but Play Core is
# not a dependency of this app and is never used (sideloaded APK, no
# deferred components). R8's missing-class check fails the release
# build on those references; -dontwarn is the standard Flutter fix.
-dontwarn com.google.android.play.core.**
