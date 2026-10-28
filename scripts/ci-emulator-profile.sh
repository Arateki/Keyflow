#!/usr/bin/env bash
# O perfil do avdmanager não basta: no boot frio o Android pode ficar com os recursos genéricos.
# Popups usam esses recursos mesmo na galeria imersiva (status bar de 63 px em vez dos 128 do Pixel 6).
# Fixa os overlays do próprio sistema, sem mudar layout, golden ou limiar do app.
set -euo pipefail
EVIDENCE=app/build/ci-evidence
mkdir -p "$EVIDENCE"
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
OVERLAYS=(com.android.internal.emulation.pixel_6 com.android.systemui.emulation.pixel_6)
"$ADB" shell cmd overlay list --user 0 > "$EVIDENCE/overlays-before.txt"
for overlay in "${OVERLAYS[@]}"; do
  "$ADB" shell cmd overlay enable --user 0 "$overlay"
done
"$ADB" shell cmd overlay list --user 0 > "$EVIDENCE/overlays-after.txt"
for overlay in "${OVERLAYS[@]}"; do
  grep -Fx "[x] $overlay" "$EVIDENCE/overlays-after.txt" >/dev/null || {
    echo "::error::Overlay do Pixel 6 não foi ativado: $overlay"; exit 1;
  }
done
"$ADB" shell cmd overlay lookup --user 0 android android:dimen/status_bar_height_portrait > "$EVIDENCE/status-bar-height.txt"
"$ADB" shell dumpsys window displays > "$EVIDENCE/display-before-gate.txt"
cat "$EVIDENCE/status-bar-height.txt"
