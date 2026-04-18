# TLA-SpeedAlert

테슬라 CAN 연동 **과속·단속 카메라 알림** Android 앱 (BLE GPS/속도, 공공데이터 CSV, OSRM/Mapbox 경로 등).

## 로컬 CSV (선택)

`app/src/main/assets/cameras.csv` — 저장소에 포함하지 않습니다.  
[data.go.kr](https://www.data.go.kr) 등에서 받은 표준 CSV를 위 경로에 두면 빌드·실행 시 자산에서 임포트됩니다.

## GitHub 새 저장소에 올리기

1. GitHub에서 **이름: `TLA-SpeedAlert`** 인 빈 저장소를 만듭니다 (README 추가 안 함 권장).
2. 이 폴더에서:

```bash
cd TLA-SpeedAlert
git remote add origin https://github.com/YOUR_USERNAME/TLA-SpeedAlert.git
git branch -M main
git push -u origin main
```

GitHub CLI가 있으면:

```bash
cd TLA-SpeedAlert
gh repo create TLA-SpeedAlert --public --source=. --remote=origin --push
```

`YOUR_USERNAME` 은 본인 계정으로 바꿉니다.

## 빌드

Android Studio에서 **이 디렉터리(`TLA-SpeedAlert`)를 프로젝트 루트로 열기** → Sync → Run.

Mapbox/OSRM 등 토큰·URL은 `app/build.gradle.kts`의 `BuildConfig` 를 참고하세요.
