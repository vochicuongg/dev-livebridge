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
  // Round 126: set when the system Doze dialog was launched;
  // cleared on the next resume, when the grant is confirmed.
  bool _dozeRequestPending = false;
  // Round 127: Doze exemption state, driving the battery row's
  // optional indicator (see the row below).
  bool _dozeExempt = false;

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
      unawaited(_confirmPendingDozeGrant());
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
      final bool dozeExempt = await LiveBridgePlatform.isDozeExempt();

      if (!mounted) {
        return;
      }

      setState(() {
        _listenerEnabled = listenerEnabled;
        _notificationsGranted = notificationsGranted;
        _promoted = promoted;
        _hidePromotedAccess = deviceInfo.shouldHideLiveUpdatesPromotion;
        _dozeExempt = dozeExempt;
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

  /// Round 128: the battery row goes straight to the system
  /// "stop optimizing battery usage" (Doze) dialog — the
  /// intermediate "Bật pin không hạn chế" guide dialog is
  /// deleted. Already-granted still toasts the "optimized"
  /// message; a fresh grant is confirmed on return (round 126).
  /// (_openBatterySettings stays for a future return of the
  /// Samsung path; it is currently unreferenced.)
  Future<void> _requestDozeExemption() async {
    final String status = await LiveBridgePlatform.requestDozeExemption();
    if (!mounted) {
      return;
    }
    if (status == 'granted') {
      _snack(AppStrings.of(context).batteryDozeGranted);
    } else if (status == 'failed') {
      _snack(AppStrings.of(context).batteryDozeUnavailable);
    } else if (status == 'requested') {
      // The system dialog is up; the grant is confirmed when
      // the user returns to this screen (see below).
      _dozeRequestPending = true;
    }
  }

  /// Round 126: after the user returns from the system Doze
  /// dialog, a pending request is confirmed — if the system
  /// now reports the app exempt, the same "optimized" message
  /// as the already-granted case is shown. Denials and
  /// dismissals stay silent (no nagging).
  Future<void> _confirmPendingDozeGrant() async {
    if (!_dozeRequestPending) {
      return;
    }
    _dozeRequestPending = false;
    final bool exempt = await LiveBridgePlatform.isDozeExempt();
    if (!mounted || !exempt) {
      return;
    }
    _snack(AppStrings.of(context).batteryDozeGranted);
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
      // Round 127: the battery row is OPTIONAL (the app works
      // without it, just less reliably), so its not-enabled
      // indicator uses the accent color instead of the required
      // items' warning red — visible, but not compulsory. The
      // indicator reflects the Doze exemption only: Samsung's
      // Unrestricted setting has no API to read, so it cannot
      // be shown here (round 114).
      LbListItemData(
        title: strings.batteryUnrestricted,
        showChevron: true,
        trailingIcon:
            _dozeExempt ? null : LbIconSymbol.alertOctagonFilled,
        trailingIconColor:
            _dozeExempt ? null : LbPalette.of(context).accent,
        onTap: () {
          unawaited(_requestDozeExemption());
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
