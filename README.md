# BgTube

GeckoView 로 `m.youtube.com` 을 띄우는 개인용 안드로이드(Kotlin) 앱.

- 로그인 포함 유튜브 모바일 웹 기능 그대로 사용
- 화면 꺼짐/백그라운드에서도 재생 유지: 내장 WebExtension 이 Page Visibility API 를 우회 + 포그라운드 서비스 + WakeLock
- 알림창/잠금화면 미디어 컨트롤 (이전 / 재생·일시정지 / 다음 / 닫기, 시크바)
- 실행 시 GitHub Releases 최신 버전을 확인하고 업데이트 다운로드·설치 안내
- `main` 등에 push 하면 GitHub Actions 가 APK 를 빌드해 Releases 에 올립니다 (`v1.0.<run번호>`)

## 설치
Releases 의 `BgTube-v*.apk` 를 받아 설치하세요 (arm64 기기용). 이후에는 앱 실행 시 업데이트 안내가 뜹니다.

## 서명 키 (앱 내 업데이트에 필요)
업데이트가 덮어 설치되려면 매번 같은 키로 서명해야 합니다. 키는 저장소에 넣지 않고 GitHub Secrets 로 주입합니다.
```
keytool -genkeypair -keystore release.jks -alias bgtube -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 release.jks   # → ANDROID_KEYSTORE_BASE64
```
Settings → Secrets and variables → Actions 에 `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` 를 등록하세요. 없으면 debug 키로 서명되어 첫 설치는 되지만 이후 업데이트 덮어쓰기는 실패합니다.

## 빌드
GeckoView 는 `maven.mozilla.org` 에서 받습니다. CI 가 최신 버전을 찾아 넘겨주며, 로컬에서는
`./gradlew assembleRelease -PgeckoVersion=<버전> -PgeckoArtifact=geckoview-arm64-v8a` 로 빌드합니다.
