# TLA-SpeedAlert

테슬라 CAN 연동 **과속·단속 카메라 알림** Android 앱 (BLE GPS/속도, 공공데이터 CSV, OSRM/Mapbox 경로 등).

## 로컬 CSV (선택)

`app/src/main/assets/cameras.csv` — 저장소에 포함하지 않습니다.  
[data.go.kr](https://www.data.go.kr) 등에서 받은 표준 CSV를 위 경로에 두면 빌드·실행 시 자산에서 임포트됩니다.

## GitHub 저장소

**https://github.com/kangbumhee/TLA-SpeedAlert**

이미 `origin` 이 연결되어 있으면 이후 푸시는 `git push` 만 하면 됩니다.  
다른 계정으로 옮기려면 `git remote set-url origin …` 으로 URL을 바꿉니다.

## 빌드

Android Studio에서 **이 디렉터리(`TLA-SpeedAlert`)를 프로젝트 루트로 열기** → Sync → Run.

Mapbox/OSRM 등 토큰·URL은 `app/build.gradle.kts`의 `BuildConfig` 를 참고하세요.
