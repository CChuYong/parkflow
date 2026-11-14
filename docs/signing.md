# 서명된 demo / release 빌드

제출용 `demo`는 일반 앱 서명을 사용하고 디버깅을 비활성화합니다. P 전환 후 기록 알림에 기존 시연의 NAVIGATION 분류를 사용하는 PoC 변형입니다. `release`는 해당 알림을 REMINDER로 분류합니다. 주차장 추천 알림은 둘 다 NAVIGATION입니다. 서명은 시스템 권한이나 배포 승인을 부여하지 않습니다.

JDK 17, Android build-tools 34.0.0을 준비하고 저장소 밖의 Java properties 파일에 다음 키를 설정합니다. 아래 값은 형식 예시입니다.

```properties
storeFile=/absolute/path/outside/repository/parkflow-release.p12
storePassword=<private-store-password>
keyAlias=parkflow-release
keyPassword=<private-key-password>
storeType=PKCS12
```

```sh
PARKFLOW_SIGNING_PROPERTIES=/path/outside/repository/signing.properties ./scripts/build-signed.sh demo
# 일반 release가 필요할 때만:
PARKFLOW_SIGNING_PROPERTIES=/path/outside/repository/signing.properties ./scripts/build-signed.sh release
```

명령은 해당 변형 APK 생성, lint, 단위 테스트, 서명·ZIP 정렬·non-debuggable 검증을 수행합니다. 결과는 각각 `app/build/outputs/apk/demo/app-demo.apk`, `app/build/outputs/apk/release/app-release.apk`입니다.

서명 변수가 없으면 debug는 빌드할 수 있지만, 직접 Gradle로 만든 demo/release는 unsigned입니다. 제출 APK 생성에는 위 스크립트를 사용하세요. 비밀번호는 명령줄 인자나 로그에 넣지 않습니다. 키·properties 파일은 저장소 밖에 보관하고 소유자만 읽을 수 있도록 보호합니다. 기존 키를 재사용해야 같은 서명의 앱을 업데이트할 수 있습니다.

빌드 재현 시 본인의 키로 서명하면 인증서와 APK 해시는 제출 APK와 달라집니다. 제출 APK의 정확한 바이트는 제공된 SHA256SUMS와 비교합니다. 같은 소스의 빌드 가능성과 바이트 단위 재현성은 구분합니다.
