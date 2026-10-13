#!/usr/bin/env bash
# GitHub Pages 용 다운로드 페이지 생성: make-site.sh <버전태그> <apk경로>
set -euo pipefail
TAG="$1"; APK="$2"
mkdir -p site
cp "$APK" site/BgTube.apk
SIZE=$(du -h site/BgTube.apk | cut -f1)
cat > site/index.html <<HTML
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BgTube 다운로드</title>
<style>
  :root { --bg:#0f0f14; --fg:#f3f3f7; --muted:#a3a3b5; --accent:#5eead4; --card:#1b1b25; }
  @media (prefers-color-scheme: light) { :root { --bg:#f6f6fa; --fg:#14141c; --muted:#5a5a6e; --accent:#0f766e; --card:#fff; } }
  body { margin:0; font-family: system-ui, sans-serif; background:var(--bg); color:var(--fg); }
  main { max-width:560px; margin:0 auto; padding:32px 16px 48px; }
  h1 { font-size:28px; margin:0 0 4px; }
  .sub { color:var(--muted); margin:0 0 24px; }
  a.btn { display:block; text-align:center; background:var(--accent); color:#06201c; font-weight:700;
          font-size:18px; padding:18px; border-radius:14px; text-decoration:none; }
  .card { background:var(--card); border-radius:14px; padding:16px 18px; margin-top:20px; }
  .card h2 { font-size:16px; margin:0 0 8px; }
  ol { margin:0; padding-left:20px; color:var(--muted); line-height:1.7; }
  code { word-break:break-all; }
</style>
</head>
<body>
<main>
  <h1>BgTube</h1>
  <p class="sub">${TAG} · ${SIZE} · Android arm64</p>
  <a class="btn" href="BgTube.apk" download>APK 다운로드</a>
  <div class="card">
    <h2>설치 방법</h2>
    <ol>
      <li>받은 <code>BgTube.apk</code>를 눌러 설치합니다 (내 파일 → 다운로드).</li>
      <li>"출처를 알 수 없는 앱" 허용 화면이 뜨면 허용합니다.</li>
      <li>삼성 폰이면 설정 → 보안 및 개인 정보 보호 → 자동 차단을 잠시 끕니다.</li>
      <li>Play Protect 경고가 뜨면 "무시하고 설치"를 선택합니다.</li>
    </ol>
  </div>
  <div class="card">
    <h2>받아지지 않으면</h2>
    <ol>
      <li>Chrome 대신 삼성 인터넷이나 Firefox에서 이 페이지를 열어 보세요.</li>
      <li>그래도 안 되면 PC에서 받아 USB나 클라우드로 폰에 옮기세요.</li>
    </ol>
  </div>
</main>
</body>
</html>
HTML
