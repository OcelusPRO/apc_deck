#!/usr/bin/env bash
# Test de fumée de l'app Android sur émulateur (workflow android.yml, job emulator) :
# installation, démarrage du moteur, plugins embarqués, interface rendue dans la WebView, APC virtuel,
# décodage audio Android (WAV et MP3 envoyés à la Soundboard).
set -euo pipefail
PKG=fr.ftnl.apcdeck
fail() {
  echo "::error::$*"
  adb exec-out screencap -p > screen.png || true
  echo "--- journal du moteur, de la WebView et plantages ---"
  adb logcat -d | grep -E "System.out|chromium|Console|cr_|WebView|FATAL|AndroidRuntime: (FATAL|Caused|\s+at fr)" | grep -v "uiautomator" | tail -80 || true
  echo "--- interface à l'écran (uiautomator) ---"
  adb shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n' | grep -oE '(class|text|content-desc)="[^"]+"' | sort | uniq -c | sort -rn | head -40 || true
  exit 1
}

adb install -r "$1"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -W -n $PKG/.android.MainActivity

# Le moteur écrit son journal sur la sortie standard (logcat, étiquette System.out).
url=""
for _ in $(seq 1 60); do
  url=$(adb logcat -d -s System.out | grep -oE "interface : http://127\.0\.0\.1:[0-9]+/[0-9a-f]+/" | tail -1 | sed 's/interface : //') || true
  [ -n "$url" ] && break
  adb logcat -d | grep -q "FATAL EXCEPTION" && fail "l'app a planté au démarrage"
  sleep 2
done
[ -n "$url" ] || fail "le moteur n'a pas démarré (pas d'adresse d'interface dans le journal)"
echo "interface : $url"
port=$(echo "$url" | sed -E 's#http://127\.0\.0\.1:([0-9]+)/.*#\1#')
token=$(echo "$url" | sed -E 's#.*/([0-9a-f]+)/$#\1#')
adb forward tcp:18080 tcp:"$port"
base="http://127.0.0.1:18080/$token"

sleep 5
journal=$(adb logcat -d -s System.out || true)
echo "$journal" | grep -E "INFO|WARN|ERROR" | sed 's/^.*System.out: //' | head -40
for p in "Pager" "Synthé" "Soundboard"; do
  echo "$journal" | grep -q "$p .* charg" || fail "plugin $p non chargé"
done

# Interface et pages des plugins servies par le moteur.
for u in "$base/" "$base/p/synth/index.html" "$base/p/soundboard/index.html" "http://127.0.0.1:18080/apcdeck.js"; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$u")
  [ "$code" = 200 ] || fail "GET $u -> $code"
done

# Interface rendue dans la WebView (arbre d'accessibilité).
ok=""
for _ in $(seq 1 15); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  ui=$(adb shell cat /sdcard/ui.xml 2>/dev/null || true)
  if grep -qE "Contrôle à distance|APC Deck" <<<"$ui"; then ok=1; break; fi
  sleep 2
done
[ -n "$ok" ] || fail "l'interface n'apparaît pas dans la WebView"
echo "interface affichée dans la WebView"

# APC virtuel : un appui simulé est vu dans l'état poussé à l'interface.
curl -sf -X POST -H 'Content-Type: application/json' -d '{"type":"pad","x":2,"y":3,"pressed":true}' "$base/app/cmd/simulate" >/dev/null
sleep 1
# (sorties capturées avant d'être filtrées : avec pipefail, « curl | grep -m1 » échouerait sur le SIGPIPE de curl)
events=$(timeout 3 curl -sN "$base/app/events" || true)
grep '"event":"input"' <<<"$events" | grep -q '"pads":\[26\]' || fail "appui de l'APC virtuel non pris en compte : $(grep '"event":"input"' <<<"$events" | head -1)"
echo "APC virtuel : appui pris en compte"

# Décodage audio Android : WAV et MP3 envoyés à la Soundboard, puis forme d'onde (décodée par MediaCodec).
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:duration=1" -ar 44100 tone.wav
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:duration=1" -ar 44100 -b:a 128k tone.mp3
x=0
for f in tone.wav tone.mp3; do
  data=$(base64 -w0 "$f")
  printf '{"row":0,"col":0,"x":%d,"y":0,"index":0,"last":true,"fileName":"%s","name":"%s","data":"%s"}' $x "$f" "$f" "$data" > upload.json
  curl -sf -X POST -H 'Content-Type: application/json' --data-binary @upload.json "$base/api/soundboard/call/upload" >/dev/null || fail "envoi de $f refusé"
  peaks=$(curl -sf -X POST -H 'Content-Type: application/json' -d "{\"row\":0,\"col\":0,\"x\":$x,\"y\":0}" "$base/api/soundboard/call/peaks")
  duration=$(echo "$peaks" | grep -oE '"duration":[0-9.]+' | cut -d: -f2)
  echo "$f : durée décodée $duration s"
  awk -v d="$duration" 'BEGIN { exit !(d > 0.9 && d < 1.2) }' || fail "$f : durée décodée $duration au lieu de ~1 s"
  x=$((x + 1))
done

echo "$journal" | grep -q "sortie audio ouverte" && echo "Synthé : sortie audio ouverte" || echo "::warning::Synthé : pas de sortie audio sur l'émulateur"
adb exec-out screencap -p > screen.png
adb logcat -d | grep -q "FATAL EXCEPTION" && fail "plantage pendant le test"
echo "test de fumée réussi"
