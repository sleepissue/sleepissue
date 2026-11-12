# 2026-11-12 리허설 요청 목록 최종 점검

현재 기능 기획서 5·6·7·8장과 시연 대본을 기준으로 확인했다. 기존 음성 대상·대화 처리 코드는 사용자 지시대로 유지했다. 실제 발화 확인 1건이 남는다.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| 정상·출발 피로 | 정상 끝까지 0단계, 출발 피로 첫 D단 뒤 약 15분10초 검출 회귀 통과 | Role5IntegrationTest |
| 딴짓 | 1분 이내 복귀하는 조작 공백·급조향 입력을 보완. 이상 분·튐형 기록, 추세형/휴식 유도 오판 없음 | distraction.json |
| 경유지 | 경로 안내를 준비한 제품 데모에서 실제 반영 확인, 휴식 인증·회복·스탬프1개 | guided-waypoint.json |
| 3단계 취소 | 제안·자동 추가 대기 취소 후 실제 추가 요청 없음. 위험 단계 유지 | cancel-waypoint.json |
| 파워냅 | 쉼터 도착 후 선택 시작→알람 상태→기상. 정차 조건은 유지 | powernap.json |
| 통풍 제어기 | 동의 없음 제어 거절, 운전석 0→3단 재조회, 3→0단 원복·최초 값 복구 | ventilation.json |
| 첫 실행 권한 | 실제 시스템 창에서 마이크 거절→재허용, 차량·알림 권한, 다음 화면, 하단 주행 탭 진입 확인 | permissions.json |
| 주행 잠금 | 공식 콘솔로 D단·60km/h 입력. 플랫폼 제한 true, 설정·리포트·데모 탭 비활성. P단·0km/h 뒤 설정 재개 | driving-restriction.json |
| 실제 음성 응답 | 남음. 기획서의 시연 절차대로 에뮬레이터 마이크에 직접 ‘네’·‘응’을 말해 확인 | 실제 발화·응답 시간·의도 인식은 버튼 시험과 별개 |

모든 원본은 [기기 증거 폴더](evidence/2026-11-12-role5-final-checks/)에 있다. 원본 음성·인식 문장은 저장하지 않았다. 기기 시험은 끝난 뒤 권한·동의 설정·가상 차량 조작 값·운전석 통풍 값을 복구한다.

## 내비 확인 조건

초기 시험에서는 연결·추가 호출이 돌아왔지만 경유지 목록이 계속 0개였다. 기존 4번의 임시 경로 안내 절차에서는 2회 실제 추가가 확인됐고, 같은 조건의 제품 데모도 실제 반영 확인에 성공했다. 제품에서 최종 목적지를 임의로 만들거나 바꾸지는 않는다. 반영되지 않으면 쉼터 안내로 대체한다.

현재 EngineConfig의 무응답 반영 확인 한도는 12초다. 앞선 작업 설명의 ‘2초 확인 제한’은 잘못된 설명이었다. 명시적 SDK 오류에 즉시 대응하는 것과 목록 반영을 기다리는 한도는 구분한다. 제품 수치를 늘려 통과시키지 않았다.

## 코드 보완

- 제품 데모에 기존 개입 매니저·실행 라우터·ON_DEVICE 음성·내비 요청·회복·파워냅 알람을 연결했다. SDK 없는 기본 검증과 실행 연결 검증은 구분한다.
- 수동 Hub 발행 직후 같은 신호·내비·전원 묶음을 계산한다. 질문 중 배속은 1배, 응답과 경유지 유예는 실제 경과 시간으로 처리한다.
- 내비는 호출 전에 콜백 구독을 설치하며, 반영 확인과 후속 재탐색·화면 요청의 시간 제한을 분리했다.
- 첫 실행 안내가 홈과 하단 주행 탭 모두에 적용되도록 했다. 설정 스위치의 텍스트와 조작 영역을 함께 묶었다.
- 주행 화면에는 주행 허용 선언을 넣고, 실제 제한 신호에 따라 설정·리포트·데모를 잠근다. [Pleos 공식 연동 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)와 [지역별 정책](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)에 근거한다.

## 검증·재현

전체 엔진 346개(기존 결과 재사용)·앱 453개 단위 시험, 실패·오류·skip 0. 제품·기기시험 APK 빌드 성공. 시험의 준비 조건은 결과별 파일에 기록했다. 실제 발화와 알람 소리 청취는 사람의 시연 확인으로 남는다.

```bash
cd src/android
./gradlew :engine:test :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

저장소 루트에서 권한·잠금 검증은 다음 도구를 쓴다. 잠금 도구는 공식 콘솔의 8088 포워딩이 필요하다.

```bash
python3 tools/verify_role5_permissions.py
adb forward tcp:8088 tcp:8088
python3 tools/verify_role5_driving_restrictions.py
```

통풍 기기 시험은 `role5ClimateDevice=true`, 제품 SDK 기기 시험은 `role5ConnectedDevice=true`를 명시한다. 외부 복구 도구가 필요한 권한·잠금 시험도 각 도구가 실행 인자를 전달한다. 준비 없이 일반 시험 모음에서 실행되지 않게 분리했다.
