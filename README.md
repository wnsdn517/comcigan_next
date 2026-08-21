# 컴시간알리미+ 소스코드

## 구조
표준 Gradle Android 프로젝트입니다.
- `app/src/main/java/dev/rocky/comcitime/` — 자바 소스
- `app/src/main/res/` — 리소스 (앱 아이콘, 네트워크 보안 설정 등)
- `app/src/main/AndroidManifest.xml` — 앱 매니페스트
- `app/build.gradle`, `build.gradle`, `settings.gradle` — Gradle 빌드 설정

## Android Studio에서 열기
저장소를 그대로 Android Studio에서 "Open"으로 열면 바로 인식됩니다. Gradle 동기화가
끝나면 `Run`으로 바로 실행할 수 있습니다.

## 커맨드라인 빌드
```
./gradlew assembleDebug
```
결과 APK는 `app/build/outputs/apk/debug/app-debug.apk` 에 생성됩니다.

## 자동 빌드 (GitHub Actions)
`.github/workflows/android-build.yml` 워크플로우가 `main`/`dev` 브랜치 푸시, PR, 수동 실행
(`workflow_dispatch`)마다 자동으로 빌드해서 Actions 실행 결과의 Artifacts에 올려줍니다.
- `main` 푸시: 릴리즈 APK를 빌드해서 `comcitime-release-apk`로 올립니다. `RELEASE_KEYSTORE_FILE`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` 환경변수(예: CI
  시크릿)가 모두 주어지면 그 키스토어로 서명하고, 없으면 (unsigned APK는 설치가 안 되므로)
  자동 생성되는 디버그 키로 서명해서 항상 설치 가능한 APK를 만듭니다. 정식 배포 전에는
  꼭 실제 릴리즈 키스토어를 시크릿으로 등록하세요.
- `dev` 푸시, PR, 그 외 실행: 디버그 APK를 빌드해서 `comcitime-debug-apk`로 올립니다.

## 핵심 파일 요약
- `ComciganApi.java` — comci.kr 비공식 API 클라이언트 (쿼리 인코딩, 학교검색, 시간표조회)
- `Timetable.java` — API 응답 파싱, 교사시간표 역추출 로직
- `TimetableRepository.java` — 오프라인 캐시 + 변동이력 자동 기록
- `NeisApi.java` — NEIS 공개 API 클라이언트 (급식 정보, 키는 사용자가 직접 발급)
- `MainActivity.java` — 전체 UI (시간표 그리드, 설정, 급식 탭)
- `NotificationScheduler.java` / `AlarmReceiver.java` — 알림 스케줄링
- `LiveNotifyService.java` — 상단바 실시간 표시
- `UiKit.java` — 디자인 토큰 및 애니메이션 헬퍼
- `MappingCollector.java` / `MappingDb.java` — 실내 지도 만들기(실험 기능)용 Wi-Fi/센서
  데이터 수집 및 로컬 SQLite 저장. 서버 업로드는 아직 없고, 기기에만 저장됩니다.
