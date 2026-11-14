# ParkFlow

**주차 전엔 덜 찾고, 주차 후엔 덜 헤매도록.**

기존 차량 내비게이션의 목적지를 읽어 주변 주차장을 제안하고, 선택한 주차장으로 길 안내를 연결하는 Pleos Connect 차량용 앱 PoC입니다. 최종 서비스는 차량에서 선택한 정보를 휴대폰으로 이어 주차 위치 기록과 차량 복귀를 돕는 것을 목표로 합니다.

## 제출 자료와 구현 범위

제출 버전: **0.10 / versionCode 10**, applicationId: `io.parkflow.probe`.

APK·소개 영상·소스 ZIP은 이 저장소와 함께 제공되는 제출 자료입니다. GitHub 업로드 시 저장소의 **Releases → v0.10-demo → Assets**에서 다음 파일을 제공합니다. 현재 로컬 준비본에는 원격 다운로드 링크가 없습니다.

- `ParkFlow-0.10-demo.apk` — 서명된 에뮬레이터 시연용 앱
- `ParkFlow-introduction.mp4` — 실제 차량 PoC와 모바일 목표 UX를 구분한 약 100초 소개 영상
- `ParkFlow-0.10-source.zip` — 이 저장소의 소스 스냅샷
- `release-metadata.json`, `SHA256SUMS.txt` — 소스 커밋, 빌드 설정, 파일 무결성 정보

| 구분 | 범위 |
| --- | --- |
| 실제 차량 앱 | 사용자가 시작한 내비 연동 서비스, 목적지 정보 수신, 주변 주차장 최대 3곳 비교, 체류 시간별 예상 요금, 선택한 주차장으로 기본 내비 경로 요청 |
| 실제 차량 앱의 추가 검증 기능 | 지원 환경의 기어 P 전환 시 주차 확인 제안, 사용자 확인 후 차량 내부 기록 저장, 수동 위치 메모·기록 조회 |
| 목표 UX · 모바일 프로토타입 | QR 계정 연결, 차량·서버·휴대폰 동기화, 휴대폰 사진/위치 기록, 휴대폰에서 내 차 찾기 |
| 영상의 시연용 데이터 | 차량 이동·도보·총 이동 시간 및 이를 이용한 추천 이유. 현재 APK 비교 화면은 공공자료 기반 요금과 직선거리를 표시 |

QR은 `parkflow://pair-demo?version=1`을 담은 **연결 화면 미리보기**입니다. 실제 계정 연결을 완료하지 않습니다. 영상의 모바일 화면은 이 APK에 포함되지 않습니다. 앱이 주차 위치·층수를 자동 인식하거나 결제하지 않습니다.

## 빠른 실행

1. 공식 Pleos Playground에서 **Pleos Connect 에뮬레이터**를 설치하고 실행합니다. 일반 휴대폰 에뮬레이터에는 차량 내비 서비스가 없어 전체 흐름을 검증할 수 없습니다.
2. `adb install -r ParkFlow-0.10-demo.apk`로 설치한 뒤 ParkFlow를 실행합니다.
3. QR 미리보기의 **내비로 시작하기**를 누르고 알림 권한을 허용합니다. 최초 한 번 사용자가 이 과정을 시작해야 백그라운드 내비 이벤트를 받을 수 있습니다.
4. 기본 내비에서 **카페머머** 등 삼성역 인근 목적지를 검색하고 길 안내를 시작합니다. 정차 상태에서 조작합니다.
5. 추천 알림의 **다른 주차장 보기**로 비교하거나 **이곳으로 안내**로 바로 경로를 요청합니다. 비교 화면에서는 체류 시간을 선택하고 주차장을 고릅니다.
6. 기본 내비의 목적지가 선택한 주차장으로 바뀌는지 확인합니다.

상세한 [시연 순서와 문제 해결](docs/demo-guide.md), [이번 제출 빌드의 검증 결과](docs/validation.md)를 참고하세요.

**기존 debug 앱과 서명 인증서가 다르면 덮어쓰기 설치가 실패합니다.** 기존 앱을 삭제하면 차량 내 저장 기록이 사라질 수 있으므로, 제출 APK는 별도 테스트 에뮬레이터에 설치하세요. 같은 서명키를 사용한 이전 release 0.8에서는 업데이트 설치가 가능합니다.

## 소스 빌드

| 항목 | 버전 |
| --- | --- |
| Java | JDK 17 |
| Gradle / Android Gradle Plugin | wrapper 8.7 / 8.5.2 |
| Kotlin | 1.9.25 |
| Android | minSdk 28, compileSdk·targetSdk 34, build-tools 34.0.0 |
| NaviHelper | 3.0.5, 공식 Pleos Maven 저장소 |
| 실행 검증 환경 | Pleos Connect 2.1.0 / Android 34 / arm64 |

Android SDK에 `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`가 필요합니다. `platforms/android-34/optional/android.car.jar`도 확인하세요. 에뮬레이터 이미지는 저장소에 포함하지 않습니다.

macOS/Linux:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./scripts/build.sh
```

macOS의 등록된 JDK 17 및 macOS/Linux 기본 SDK 위치는 자동 탐색합니다. 별도의 부모 폴더나 개인 작업 경로는 필요하지 않습니다. 최초 빌드에는 Google/Maven Central/Pleos Maven/Gradle 다운로드를 위한 네트워크가 필요합니다.

명령은 debug APK 생성, Android lint, 단위 테스트를 수행합니다. 결과는 `app/build/outputs/apk/debug/app-debug.apk`입니다. Windows는 동일한 환경 변수를 설정한 뒤 `gradlew.bat assembleDebug lintDebug testDebugUnitTest`를 사용하세요. Windows 실행 자체는 검증하지 않았습니다.

## 제출용 빌드와 일반 release

| 빌드 | 디버깅 | 서명 | P 전환 후 기록 제안 알림 |
| --- | --- | --- | --- |
| debug | 가능 | Android debug 키 | NAVIGATION 실험 분류 |
| demo · 제출용 | 불가 | 외부 개인 서명키 | NAVIGATION 실험 분류, 기존 시연과 동일 |
| release | 불가 | 외부 개인 서명키 | REMINDER 분류 |

**주차장 추천 알림은 세 빌드 모두 NAVIGATION 분류입니다.** 위 차이는 P 전환 후 기록 제안 알림에만 해당합니다. demo 빌드는 시스템 서명이나 차량 UI 정책 면제를 받지 않으며, 팝업 표시와 분류의 실제 배포 적합성은 별도 확인 대상입니다. 앱은 자체 오버레이를 사용하지 않습니다.

```sh
PARKFLOW_SIGNING_PROPERTIES=/path/outside/repository/signing.properties ./scripts/build-signed.sh demo
```

[서명 설정](docs/signing.md)에 따라 본인의 키를 사용하세요. 제출자의 개인키와 비밀번호는 제공하지 않습니다. APK를 단순히 실행하는 데에는 키가 필요하지 않습니다.

## 구조와 자료

- `app/src/main/java/io/parkflow/probe/` — 차량 UI, 내비·알림 연결, 주차 후보·상태·기록 처리
- `app/src/test/` — 목적지 복구, 추천 액션, 주차 상태, 기록, 사용자 화면 전환 단위 테스트
- `app/src/main/assets/gangnam-parking.json` — 강남구 과거 공공 주차장 자료 50건
- [구현 구조 및 권한](docs/architecture.md)
- [데이터 출처와 요금 계산](docs/data-provenance.md)
- [디자인·폰트 출처](docs/pleos-design-alignment.md)
- [제3자 자료 고지](THIRD_PARTY_NOTICES.md)

공공자료는 **2025-09-05 기준**이며 현재 영업·요금·빈자리·진입구를 보장하지 않습니다. 앱 상세 화면은 정차 중 이용하며 주행 중 복잡한 비교 조작을 전제하지 않습니다. 실차 검증과 앱 마켓 배포 심사는 완료하지 않았습니다.
