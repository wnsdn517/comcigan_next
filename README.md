# 컴시간알리미+ 소스코드

## 구조
- `src/dev/rocky/comcitime/` — 자바 소스 12개 파일
- `res/xml/network_security_config.xml` — comci.kr 평문 HTTP 허용 설정
- `AndroidManifest.xml` — 앱 매니페스트

## 참고: Gradle 프로젝트가 아닙니다
이 코드는 Android Studio용 Gradle 프로젝트가 아니라, `javac` + `d8` + `aapt2`를 직접 호출해서
빌드한 것입니다 (Gradle 없이). 그대로 Android Studio에 열면 인식이 안 됩니다.

**Android Studio에서 쓰시려면**: 새 프로젝트(Empty Views Activity, Java) 만드신 다음,
1. `src/dev/rocky/comcitime/*.java` 전부를 `app/src/main/java/dev/rocky/comcitime/`에 복사
2. `res/xml/network_security_config.xml`을 `app/src/main/res/xml/`에 복사
3. `AndroidManifest.xml` 내용을 `app/src/main/AndroidManifest.xml`에 병합 (패키지명, 권한, 액티비티/리시버/서비스 등록, `networkSecurityConfig` 속성 참고)
4. `minSdkVersion 26` 이상으로 설정

## 직접 빌드하시려면 (Gradle 없이, 지금까지 쓴 방식)
Android SDK의 `aapt2`, `d8`(또는 r8.jar), `apksigner`, 그리고 `android.jar`가 필요합니다.
대략적인 순서:
```
javac -cp android.jar -d out/classes src/dev/rocky/comcitime/*.java
d8 --release --min-api 26 --lib android.jar --output out/dex out/classes/dev/rocky/comcitime/*.class
aapt2 link -o base.apk -I android.jar --manifest AndroidManifest.xml -R res-compiled/*.flat --min-sdk-version 26 --target-sdk-version 34
# base.apk에 classes.dex 추가 후 apksigner로 서명
```

## 핵심 파일 요약
- `ComciganApi.java` — comci.kr 비공식 API 클라이언트 (쿼리 인코딩, 학교검색, 시간표조회)
- `Timetable.java` — API 응답 파싱, 교사시간표 역추출 로직
- `TimetableRepository.java` — 오프라인 캐시 + 변동이력 자동 기록
- `NeisApi.java` — NEIS 공개 API 클라이언트 (급식 정보, 키는 사용자가 직접 발급)
- `MainActivity.java` — 전체 UI (시간표 그리드, 설정, 급식 탭)
- `NotificationScheduler.java` / `AlarmReceiver.java` — 알림 스케줄링
- `LiveNotifyService.java` — 상단바 실시간 표시
- `UiKit.java` — 디자인 토큰 및 애니메이션 헬퍼
