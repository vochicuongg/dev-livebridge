#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="${ROOT_DIR}/assets/icons"
RES_DIR="${ROOT_DIR}/android/app/src/main/res"
LAUNCHER_CONFIG="${ROOT_DIR}/flutter_launcher_icons.yaml"

MASTER_ICON="${ASSETS_DIR}/icon-master.png"
FOREGROUND_ICON="${ASSETS_DIR}/icon-foreground.png"
MONOCHROME_ICON="${ASSETS_DIR}/icon-monochrome.png"
NOTIFICATION_ICON="${ASSETS_DIR}/notification-icon.png"

if ! command -v sips >/dev/null 2>&1; then
  echo "Error: 'sips' is required (macOS built-in image tool)." >&2
  exit 1
fi

if ! command -v flutter >/dev/null 2>&1; then
  echo "Error: 'flutter' is required." >&2
  exit 1
fi

if [[ ! -f "${LAUNCHER_CONFIG}" ]]; then
  echo "Error: missing launcher config: ${LAUNCHER_CONFIG}" >&2
  exit 1
fi

for required in \
  "${MASTER_ICON}" \
  "${FOREGROUND_ICON}" \
  "${MONOCHROME_ICON}" \
  "${NOTIFICATION_ICON}"; do
  if [[ ! -f "${required}" ]]; then
    echo "Error: missing required file: ${required}" >&2
    exit 1
  fi
done

resize_square() {
  local src="$1"
  local dst="$2"
  local size="$3"
  mkdir -p "$(dirname "${dst}")"
  sips -s format png -z "${size}" "${size}" "${src}" --out "${dst}" >/dev/null
}

prepare_monochrome_icon() {
  local dst="$1"
  mkdir -p "$(dirname "${dst}")"

  if command -v python3 >/dev/null 2>&1; then
    if python3 - "${MONOCHROME_ICON}" "${dst}" <<'PY'
import sys
from pathlib import Path

try:
    from PIL import Image, ImageOps
except Exception:
    sys.exit(3)

src = Path(sys.argv[1])
dst = Path(sys.argv[2])

img = Image.open(src).convert("RGBA")
r, g, b, a = img.split()
hist = a.histogram()
total = sum(hist) or 1
mostly_opaque = sum(hist[250:]) / total > 0.97
has_real_transparency = sum(hist[:5]) / total > 0.02

if mostly_opaque and not has_real_transparency:
    lum = ImageOps.grayscale(img.convert("RGB"))
    lum_hist = lum.histogram()
    bg_l = max(range(256), key=lambda idx: lum_hist[idx])
    light = lum.point(lambda p: max(0, p - bg_l))
    dark = lum.point(lambda p: max(0, bg_l - p))

    light_energy = sum(i * count for i, count in enumerate(light.histogram()))
    dark_energy = sum(i * count for i, count in enumerate(dark.histogram()))
    alpha = light if light_energy >= dark_energy else dark

    alpha = ImageOps.autocontrast(alpha)
    alpha = alpha.point(lambda p: 0 if p < 22 else p)
else:
    alpha = a

out = Image.new("RGBA", img.size, (255, 255, 255, 0))
out.putalpha(alpha)
out.save(dst)
PY
    then
      return
    fi
  fi

  cp "${MONOCHROME_ICON}" "${dst}"
}

generate_launcher_icons() {
  (cd "${ROOT_DIR}" && flutter pub get >/dev/null)
  (cd "${ROOT_DIR}" && flutter pub run flutter_launcher_icons -f "${LAUNCHER_CONFIG}" >/dev/null)

  mkdir -p "${RES_DIR}/drawable-nodpi"
  prepare_monochrome_icon "${RES_DIR}/drawable-nodpi/launcher_icon_monochrome_manual.png"

  mkdir -p "${RES_DIR}/mipmap-anydpi-v26"
  cat > "${RES_DIR}/mipmap-anydpi-v26/launcher_icon.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@color/ic_launcher_background"/>
  <foreground>
      <inset
          android:drawable="@drawable/ic_launcher_foreground"
          android:inset="14%" />
  </foreground>
  <monochrome>
      <inset
          android:drawable="@drawable/launcher_icon_monochrome_manual"
          android:inset="14%" />
  </monochrome>
</adaptive-icon>
EOF
}

generate_notification_icons() {
  local specs=(
    "drawable-mdpi:24"
    "drawable-hdpi:36"
    "drawable-xhdpi:48"
    "drawable-xxhdpi:72"
    "drawable-xxxhdpi:96"
  )

  for spec in "${specs[@]}"; do
    local folder="${spec%%:*}"
    local size="${spec##*:}"
    resize_square \
      "${NOTIFICATION_ICON}" \
      "${RES_DIR}/${folder}/ic_stat_liveupdate.png" \
      "${size}"
  done
}

generate_launcher_icons
generate_notification_icons

echo "Android icon assets generated successfully."
echo "Launcher/adaptive icons generated via flutter_launcher_icons."
echo "Notification icons updated: drawable-*/ic_stat_liveupdate.png"
