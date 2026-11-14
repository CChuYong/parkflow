# 서명된 demo / release 빌드

제출용은 Gradle의 **release** 변형이며 일반 앱 서명을 사용하고 디버깅을 비활성화합니다. 0.11 release에서는 검증된 시연 동작을 유지하기 위해 `PARKING_NAV_POC=true`를 사용합니다. 주차장 추천과 P 전환 후 기록 제안 모두 NAVIGATION 분류를 사용하며, 이 PoC 분류는 시스템 권한이나 배포 승인을 부여하지 않습니다. 이전 demo 별칭은 로컬 호환용으로만 유지합니다.

JDK 17, Android build-tools 34.0.0을 준비하고 저장소 밖의 Java properties 파일에 다음 키를 설정합니다. 아래 값은 형식 예시입니다.

```properties
storeFile=/absolute/path/outside/repository/parkflow-release.p12
storePassword=<private-store-password>
keyAlias=parkflow-release
keyPassword=<private-key-password>
storeType=PKCS12
```

```sh
PARKFLOW_SIGNING_PROPERTIES=/path/outside/repository/signing.properties ./scripts/build-signed.sh release
```

명령은 해당 변형 APK 생성, lint, 단위 테스트, 서명·ZIP 정렬·non-debuggable 검증을 수행합니다. 제출 결과는 `app/build/outputs/apk/release/app-release.apk`입니다. 이 파일을 저장소의 `apk/클로버_ParkFlow_release.apk`에 복사합니다. 원본 빌드 결과와 제출 파일의 SHA-256이 동일해야 합니다.

서명 변수가 없으면 debug는 빌드할 수 있지만, 직접 Gradle로 만든 demo/release는 unsigned입니다. 제출 APK 생성에는 위 스크립트를 사용하세요. 비밀번호는 명령줄 인자나 로그에 넣지 않습니다. 키·properties 파일은 저장소 밖에 보관하고 소유자만 읽을 수 있도록 보호합니다. 기존 키를 재사용해야 같은 서명의 앱을 업데이트할 수 있습니다.

빌드 재현 시 본인의 키로 서명하면 인증서와 APK 해시는 제출 APK와 달라집니다. 제출 APK의 정확한 바이트는 제공된 SHA256SUMS와 비교합니다. 같은 소스의 빌드 가능성과 바이트 단위 재현성은 구분합니다.
