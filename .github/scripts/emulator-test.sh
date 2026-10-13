#!/usr/bin/env bash
# 에뮬레이터 안에서 실행: 설치 → 실행 → 스크린샷 → 백그라운드/화면 꺼짐 재생 확인.
# 모든 adb 호출에 타임아웃을 걸어 한 단계가 멈춰도 나머지 진단을 남긴다.
set -uo pipefail
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
OUT=out/screenshots
mkdir -p "$OUT"
APK="$1"
PKG=app.bgtube

A() { timeout 60 adb "$@"; }
shot() {
  timeout 45 adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null && [ -s "$OUT/$1.png" ] && return 0
  # 게스트의 screencap 이 멈추면 에뮬레이터 콘솔로 호스트 쪽에서 직접 캡처
  local d=/tmp/emu_shot_$1; mkdir -p "$d"
  timeout 60 adb emu screenrecord screenshot "$d" >/dev/null 2>&1
  local f; f=$(ls "$d"/* 2>/dev/null | head -1)
  if [ -n "$f" ] && [ -s "$f" ]; then cp "$f" "$OUT/$1.png"; return 0; fi
  echo "screenshot failed: $1"; rm -f "$OUT/$1.png"; return 0
}
diag() {
  echo "--- diag: $1 ---"
  A shell "pidof $PKG; dumpsys activity processes | grep -E 'ANR|Crash' | head -5" 2>&1 | head -10
  A logcat -d -t 300 2>/dev/null | grep -E "BgTube|AndroidRuntime|FATAL|ANR in|Gecko|libxul" | tail -25
}

A install -r "$APK"
A shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
A shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
A logcat -c

# 1) 앱 서랍에서 아이콘 확인
A shell input keyevent KEYCODE_HOME; sleep 2
A shell input swipe 540 1900 540 500 300; sleep 2
shot 01-app-drawer-icon

# 2) 실행
A shell am start -n $PKG/.MainActivity
for i in 1 2 3 4 5 6; do sleep 10; A shell pidof $PKG >/dev/null || { echo "app process gone"; break; }; done
diag after-launch
shot 02-youtube-home

# 3) 영상 열기 + 재생
A shell am start -a android.intent.action.VIEW -d "https://m.youtube.com/watch?v=jNQXAC9IVRw" $PKG
sleep 30
shot 03-video-page
W=$(A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
A shell input tap $(( ${W%x*} / 2 )) $(( ${W#*x} * 17 / 100 ))
sleep 15
shot 04-playing
diag after-play

# 4) 백그라운드 + 화면 끄기
A shell input keyevent KEYCODE_HOME; sleep 5
A shell input keyevent KEYCODE_SLEEP; sleep 10
A shell dumpsys media_session > "$OUT/media_session_after_sleep.txt" 2>&1
A shell "dumpsys audio | grep -iE 'player|state'" > "$OUT/audio_after_sleep.txt" 2>&1
A shell input keyevent KEYCODE_WAKEUP; sleep 3
shot 05-lockscreen
A shell cmd statusbar expand-notifications; sleep 3
shot 06-notification-controls
A shell cmd statusbar collapse

A logcat -d 2>/dev/null | grep -E "BgTube|AndroidRuntime|FATAL|ANR in|MediaSession|GeckoRuntime|GeckoConsole|WebExtension|bgplay" | tail -300 > "$OUT/app-logcat.txt"
grep -E "state=PlaybackState|package=app.bgtube" "$OUT/media_session_after_sleep.txt" | head -10 || true
exit 0
