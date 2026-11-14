# 구현 구조와 SDK 역할

이 저장소는 차량 Android 앱만 포함합니다. 모바일 앱, 계정 서버와 영상 제작 프로젝트는 포함하지 않습니다.

| 구성 | 역할 |
| --- | --- |
| MainActivity / ParkingUi | 주차 단계별 네이티브 Android Views, 비교·확인·기록 화면 |
| ProbeService | 사용자가 시작한 foreground 서비스에서 NaviHelper 연결, 목적지 정보 조회, 추천 알림과 경로 요청 |
| ParkingRecommendation / RecommendationActions | 공공자료 후보·요금 추정, 문맥·유효기간·일회성 액션 검증 |
| ParkingVisit / ParkingFlow / UserHandoffs | 기어 변화·주차 흐름·사용자 화면 전환 상태 처리 |
| VisitStore | 차량 앱 내부 SharedPreferences의 로컬 JSON 기록 |

## 내비게이션과 차량 상태

NaviHelper **3.0.5**의 콜백을 계기로 `getDestinationInfo()`를 호출해 목적지 이름·주소·좌표를 읽고, 주차장 선택 시 `requestRoute(RouteInfo(...))`로 기본 내비에 경로를 요청합니다. SDK의 `onRouteEnded` 콜백을 실제 도착으로 해석하지 않습니다. 경로 요청 후 목적지 재조회로 선택 지점과 일치하는지 확인합니다.

기어 정보는 Pleos Vehicle SDK가 아니라 **AAOS의 공개 CarPropertyManager / GEAR_SELECTION**으로 읽습니다. 지원되지 않으면 수동 확인을 유지합니다. FusedLocation·STT·TTS·차량 카메라 SDK는 현재 사용하지 않습니다.

앱은 부팅 시 자동 실행되지 않습니다. 사용자가 ParkFlow에서 한 번 주차 준비를 시작한 뒤 내비가 전면에 있는 동안 이벤트를 받습니다. 추천 알림과 기록 알림은 Android 시스템 알림이며, 각 버튼은 PendingIntent로 연결됩니다. 실제 표시 형태는 Pleos 정책에 따릅니다.

## 권한

| Manifest 권한 | 용도 |
| --- | --- |
| POST_NOTIFICATIONS | 사용자 동의를 받은 추천·주차 확인 알림 |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE | 사용자가 시작한 내비 이벤트 수신 유지 |
| pleos.car.permission.NAVI_ROUTE / NAVI_ROUTE_SEARCH | 내비 정보·목적지/경로 연동 |
| pleos.car.permission.NAVI_CUSTOM_MAP | 현재 NaviHelper 연동에서 선언하는 내비 권한; 앱 자체 지도·오버레이를 구현했다는 의미는 아님 |
| android.car.permission.CAR_POWERTRAIN | 지원 차량의 기어 선택 상태 읽기 |

앱 자체 서버 통신·백그라운드 위치 수집은 구현하지 않았습니다. 시스템 서명·접근성 서비스·화면 오버레이·distractionOptimized 예외는 사용하지 않습니다.

## 데이터와 예외 처리

추천은 자료의 직선거리 1km 이내 최대 3곳을 거리순으로 제시합니다. 체류 시간별 요금은 자료의 기본/추가 단위만 반영합니다. 할인·실시간 빈자리·일주차권·도보 경로는 반영하지 않습니다.

알림 버튼은 목적지/후보/체류 시간과 묶인 일회성 토큰 및 120초 유효기간으로 오래된 동작을 차단합니다. P 전환은 주차 확인 제안만 만들고, 사용자 확인 전에 기록을 저장하지 않습니다. 저장 실패는 성공으로 표시하지 않으며 손상된 기존 기록을 조용히 덮어쓰지 않습니다.
