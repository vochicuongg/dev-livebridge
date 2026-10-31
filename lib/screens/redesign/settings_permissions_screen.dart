import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/app_models.dart';
import '../../models/promoted_notification_access.dart';
import '../../platform/livebridge_platform.dart';
import '../../theme/livebridge_tokens.dart';
import '../../utils/livebridge_haptics.dart';
import '../../widgets/redesign/lb_detail_screen.dart';
import '../../widgets/redesign/lb_icon.dart';
import '../../widgets/redesign/lb_list_component.dart';
import '../../widgets/redesign/lb_toast.dart';

class SettingsPermissionsScreen extends StatefulWidget {
  const SettingsPermissionsScreen({super.key});

  @override
  State<SettingsPermissionsScreen> createState() =>
      _SettingsPermissionsScreenState();
}

class _SettingsPermissionsScreenState extends State<SettingsPermissionsScreen>
    with WidgetsBindingObserver {
  bool _listenerEnabled = false;
  bool _notificationsGranted = false;
  PromotedNotificationAccess _promoted = const PromotedNotificationAccess(
    status: 'unknown',
  );
  bool _hidePromotedAccess = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_loadState());
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_loadState());
    }
  }

  void _snack(String value) {
    if (!mounted) {
      return;
    }
    showLbToast(context, message: value);
  }

  Future<void> _loadState() async {
    try {
      final bool listenerEnabled =
          await LiveBridgePlatform.isNotificationListenerEnabled();
      final bool notificationsGranted =
          await LiveBridgePlatform.isNotificationPermissionGranted();
      final promoted = await LiveBridgePlatform.getPromotedNotificationAccess();
      final DeviceInfo deviceInfo = await LiveBridgePlatform.getDeviceInfo();

      if (!mounted) {
        return;
      }

      setState(() {
        _listenerEnabled = listenerEnabled;
        _notificationsGranted = notificationsGranted;
        _promoted = promoted;
        _hidePromotedAccess = deviceInfo.shouldHideLiveUpdatesPromotion;
      });
    } catch (_) {}
  }

  Future<void> _requestNotificationPermission() async {
    unawaited(LiveBridgeHaptics.confirm());
    final bool granted =
        await LiveBridgePlatform.requestNotificationPermission();
    if (!mounted) {
      return;
    }
    final AppStrings strings = AppStrings.of(context);
    _snack(granted ? strings.permissionGranted : strings.permissionDenied);
    await _loadState();
  }

  Future<void> _openListenerSettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    final bool opened =
        await LiveBridgePlatform.openNotificationListenerSettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).listenerUnavailable);
  }

  Future<void> _openAppNotificationSettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    final bool opened = await LiveBridgePlatform.openAppNotificationSettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).notificationsUnavailable);
  }

  Future<void> _openBatterySettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    final bool opened = await LiveBridgePlatform.openBatterySettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).batterySettingsUnavailable);
  }

  /// Round 119: tapping the battery row first shows a SystemUI-style
  /// guide — why the permission matters — because a third-party
  /// app cannot land directly on the per-app Battery page.
  /// Round 120: the guide offered the system's one-tap Doze
  /// dialog as a supplementary layer (it does not replace
  /// Samsung's Unrestricted).
  /// Round 123: the guide is Doze-only now — the "Open Settings"
  /// button and the numbered Samsung steps are gone, and the
  /// Doze button is relabeled "Allow". "Later" just dismisses.
  /// (_openBatterySettings stays for a future return of the
  /// Samsung path; it is currently unreferenced.)
  Future<void> _showBatteryGuide() async {
    final AppStrings strings = AppStrings.of(context);
    final String? choice = await showDialog<String>(
      context: context,
      builder: (BuildContext dialogContext) {
        return AlertDialog(
          title: Text(strings.batteryGuideTitle),
          content: Text(strings.batteryGuideBody),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text(strings.batteryGuideLater),
            ),
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop('doze'),
              child: Text(strings.batteryGuideAllow),
            ),
          ],
        );
      },
    );
    if (!mounted) {
      return;
    }
    if (choice == 'doze') {
      await _requestDozeExemption();
    }
  }

  Future<void> _requestDozeExemption() async {
    final String status = await LiveBridgePlatform.requestDozeExemption();
    if (!mounted) {
      return;
    }
    if (status == 'granted') {
      _snack(AppStrings.of(context).batteryDozeGranted);
    } else if (status == 'failed') {
      _snack(AppStrings.of(context).batteryDozeUnavailable);
    }
    // "requested": the system dialog is up; the user decides there.
  }

  Future<void> _openPromotedSettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    final bool opened =
        await LiveBridgePlatform.openPromotedNotificationSettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).liveUpdatesUnavailable);
  }

  LbListItemData _buildPermissionItem({
    required String title,
    required bool enabled,
    required VoidCallback onTap,
  }) {
    return LbListItemData(
      title: title,
      trailingIcon: enabled ? null : LbIconSymbol.alertOctagonFilled,
      trailingIconColor: enabled ? null : LbPalette.of(context).warning,
      onTap: onTap,
    );
  }

  @override
  Widget build(BuildContext context) {
    final AppStrings strings = AppStrings.of(context);

    final List<LbListItemData> permissionItems = <LbListItemData>[
      _buildPermissionItem(
        title: strings.listenerAccess,
        enabled: _listenerEnabled,
        onTap: () {
          unawaited(_openListenerSettings());
        },
      ),
      _buildPermissionItem(
        title: strings.postNotifications,
        enabled: _notificationsGranted,
        onTap: () {
          if (_notificationsGranted) {
            unawaited(_openAppNotificationSettings());
          } else {
            unawaited(_requestNotificationPermission());
          }
        },
      ),
      LbListItemData(
        title: strings.batteryUnrestricted,
        showChevron: true,
        onTap: () {
          unawaited(_showBatteryGuide());
        },
      ),
      if (!_hidePromotedAccess)
        LbListItemData(
          title: strings.liveUpdatesAccess,
          subtitle: _promoted.status == "unavailable"
              ? strings.promotionUnavailableStatus
              : _promoted.status == "unknown"
              ? strings.promotionUnknownStatus
              : null,
          description: !_promoted.granted && !_promoted.needsPermission
              ? strings.promotionUnavailableHelp
              : null,
          trailingIcon: _promoted.needsPermission
              ? LbIconSymbol.alertOctagonFilled
              : null,
          trailingIconColor: _promoted.needsPermission
              ? LbPalette.of(context).warning
              : null,
          showChevron: _promoted.settingsAvailable,
          onTap: _promoted.settingsAvailable
              ? () {
                  unawaited(_openPromotedSettings());
                }
              : null,
        ),
    ];

    return LbDetailScreen(
      title: strings.accessTitle,
      children: <Widget>[
        LbListComponent(
          items: permissionItems,
          rowHeight: LbSpacing.recentRowHeight,
          extendDividersToEnd: true,
        ),
      ],
    );
  }
}
