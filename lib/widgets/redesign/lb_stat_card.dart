import 'package:flutter/material.dart';

import '../../theme/livebridge_tokens.dart';
import 'lb_icon.dart';

class LbStatCard extends StatelessWidget {
  const LbStatCard({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    this.isWarning = false,
    this.onTap,
  });

  final LbIconSymbol icon;
  final String title;
  final String subtitle;
  final bool isWarning;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final LbPalette palette = LbPalette.of(context);
    final Color iconColor = isWarning ? palette.warning : palette.accent;
    final Color subtitleColor = isWarning
        ? palette.warning
        : palette.textSecondary;

    return Semantics(
      button: onTap != null,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: AspectRatio(
          aspectRatio: LbSpacing.statCardAspectRatio,
          child: Container(
            clipBehavior: Clip.antiAlias,
            padding: const EdgeInsets.all(LbSpacing.statCardPadding),
            decoration: BoxDecoration(
              color: isWarning ? palette.warningSurface : palette.surface,
              borderRadius: BorderRadius.circular(LbRadius.smallCard),
            ),
            // Round 138: the card height is fixed by its aspect ratio
            // while the two text lines grow with the system font size,
            // so at a larger text scale the stack overflowed by a few
            // pixels (debug "BOTTOM OVERFLOWED" banner). Scale the
            // whole stack down to fit instead; at the default text
            // scale nothing changes (scale factor 1).
            child: FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.center,
              child: Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: <Widget>[
                  LbIcon(
                    symbol: icon,
                    size: LbSpacing.statCardIcon,
                    color: iconColor,
                  ),
                  const SizedBox(height: LbSpacing.statCardIconGap),
                  _LbStatCardText(
                    text: title,
                    style: LbTextStyles.statTitle.copyWith(
                      color: palette.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Transform.translate(
                    offset: const Offset(0, -2),
                    child: _LbStatCardText(
                      text: subtitle,
                      style: LbTextStyles.statCaption.copyWith(
                        color: subtitleColor,
                      ),
                    ),
                  ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _LbStatCardText extends StatelessWidget {
  const _LbStatCardText({required this.text, required this.style});

  final String text;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: FittedBox(
        fit: BoxFit.scaleDown,
        child: Text(
          text,
          maxLines: 1,
          softWrap: false,
          textAlign: TextAlign.center,
          style: style,
        ),
      ),
    );
  }
}
