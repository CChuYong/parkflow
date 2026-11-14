# 제출 APK

[클로버_ParkFlow_release.apk](클로버_ParkFlow_release.apk)는 공식 제출 안내의 `팀명_앱이름_release.apk` 규칙을 따른 **0.11 release** APK입니다.

- 빌드: `./scripts/build-signed.sh release` (외부 서명 설정 필요)
- 실행 파일: `applicationId=io.parkflow.probe`, versionCode 11, 디버깅 비활성화
- [파일 해시](SHA256SUMS.txt), [버전·서명·검증 메타데이터](release-metadata.json)
- 설치·시연 방법: [저장소 README](../README.md)

`buildSourceCommit`은 APK를 생성한 앱 소스의 커밋입니다. APK·문서가 추가된 최종 제출 커밋은 `git rev-parse v0.11-release`로 확인하며, 접수 메일에는 그 최종 커밋을 적습니다. 두 커밋의 `app` 소스 트리는 같습니다. 개인 서명키와 비밀번호는 포함하지 않습니다.
