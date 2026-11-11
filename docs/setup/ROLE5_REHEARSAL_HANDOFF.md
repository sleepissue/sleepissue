# 5번 제품 화면·데모 연결 인계 (2026-11-11)

## 실행 방법과 소유권

Android Studio에서는 `src/android/`를 연다. `assembleDebug` APK의 AwakeDrive 런처가 제품 화면이다. 하단 데모 탭에서 6종과 1·10·60배를 고르고 불러오기 → 재생한다. 재생 중에는 상단 **데모 종료**로 실제 모드로 돌아간다. 실제 감시 서비스 실행 중에는 데모를 시작하지 않는다.

- 실제 입력: `RealTripMonitorService.pipeline`을 `LiveDriveUiSession`이 관찰한다. 화면에서 별도 판단·음성·Room 소유자를 만들지 않는다.
- 데모 입력: `ProductDemoSession` → `ScenarioDemoPlayer` → `VehicleSignalHub` → `DemoUiSession`이다. 기존 순수 판단·휴식·보상 규칙을 사용하지만 실제 서비스 실행 라우터와 같은 조립은 아니다.
- 데모는 실제 TTS·STT·내비 실행 및 실제 보상 DB 기록을 수행하지 않는다. 화면 경고·인증 결과를 실제 SDK 실행 성공으로 해석하지 않는다.
- 시나리오 단일 원본은 `data/scenarios/`이며 제품·디버그 APK assets에 포함한다. 개발 도구의 기존 디버그 신호 검증 진입점은 보존한다.

## 데이터 반영

6종 모두 27,100개(10 Hz, 45분10초), 위치·P/D·OFF/RESUME·회전 거리 5,000m·실내 온도 23.5°C를 포함한다. normal/distraction/talk/assist는 한국 시각 10:00, drowsy/departure는 22:55 시작이다. talk는 16분대 급조향, assist는 20분 크루즈와 해제 후 10초 조향 고정·급조향을 포함한다. 좌표는 현재 쉼터 CSV의 첫 지점과 차로 방위각으로 합성한 시연 입력이며 실제 주행 궤적이 아니다.

## 2번 엔진 담당에게 전달

`docs/fatigue-engine/12-demo-expected.md`는 2026-11-11 원격 fetch 후 확인한 로컬·origin 브랜치에 없다. 기대 시점은 해당 문서가 공개된 뒤 대조한다. 현재 `FatigueAssessmentRunner`에도 `epochOffsetMs` 속성이 없다.

현재 제품 데모는 `DemoUiSession.observe()`에서 `frame.tMs + startEpochMs`를 `FatigueBridge`에 전달하고, 출발 컨디션도 같은 절대 시각으로 보정한다. `runner.epochOffsetMs = scenario.startEpochMs` 방식으로 옮길 때 이 기존 보정을 제거하거나 경계를 맞춰 이중 가산을 방지해야 한다. 내비·운행 상태의 시간축도 함께 검토한다. 제품 데모가 아직 runner를 사용하지 않으므로 한 줄만 추가해서 통합 완료가 되지는 않는다.

## 남은 연결과 시연 제한

- 정상 시나리오의 기기 재생에서 45:10 종료 순간 1단계(SAFETY_EVENT)가 관찰됐다. 순수 모델 직결 단위 시험의 끝까지 0단계 결과와 다르므로 제품 Player→Hub 경계의 별도 결함이다. 종료 완료 통과를 기능 전체 통과로 표시하지 않는다.

- 제품 데모의 음성 질문·응답 시간·회복 확인·내비 경유지 실행 연결.
- 제품 데모의 진행 방향 계산. 현재 JSON 로더는 위치를 넘기지만 heading을 만들지 않고, `RestAreaSelector`는 heading이 없으면 후보를 반환하지 않는다. 기기에서도 앞쪽 쉼터 없음이 관찰됐다.
- 실제 화면의 동승자 응답은 현재 NOT_CONNECTED. 연령대·확인 빈도 설정도 실제 서비스 입력 연결이 필요하다.
- 실제 서비스의 대화 감지 입력은 `speechDetected=false` 고정이다. talk 데이터의 동승자 착석·급조향만으로 실제 대화 확인 장면이 완성되지 않는다.
- 통풍 동의 UI·실제 서비스 경로는 있으나 데모 입력에는 통풍 값이 없고, 데모에서 실제 통풍 제어를 수행하지 않는다.
- 마이크 거절 대체, 알림 권한, 실제 통풍 반영·원복, 주행 제한 및 첫 실행 전체 흐름은 별도 기기 확인이 필요하다.
- 파워냅 시작·기상 버튼과 타이머는 있으나 전체 6종 기본 재생 시험은 파워냅 버튼 조작·알람 소리를 검증하지 않는다.
- 60배는 요청 배속이다. 호스트 부하에 따른 실제 완료 시간은 기기 보고서로 구분한다.

## 이번 오류 수정과 검증

기기에서 45분 시나리오를 불러오면 192MiB 힙 한도에서 메모리 부족으로 종료됐다. JSON samples를 한 개씩 검증하고 배속을 파싱 모델에서 변경해 거대한 JSON 트리의 중복 생성을 제거했다. 로드는 화면 스레드 밖에서 실행한다. 재생 중 종료 버튼과 모드·세션 전환 시 그래프 초기화, 실제 모드의 DEMO 고정 문구도 수정했다.

`./gradlew :engine:test :app:testDebugUnitTest assembleDebug` 성공. 엔진 346개·앱 440개, 실패·오류·skip 0개(엔진은 기존 결과 재사용, 앱은 전체 실행). 스트리밍 로더의 메타데이터 순서·중복 samples 회귀 시험을 추가했다. Pleos v2.1.0은 호스트 GPU 초기화에서 종료되어 소프트웨어 그래픽 모드로 콜드 부팅했다. 앱 삭제·DB 초기화·CRN 재주입은 하지 않았다.

6종 기기 시험은 `ProductScenariosDeviceTest`다. `assembleDebugAndroidTest` 후 아래와 같이 실행한다. 이 시험은 제품 세션을 기기 프로세스에서 실행하며 UI 터치·실제 SDK 실행 시험을 대신하지 않는다.

```bash
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.sleepissue.awakedrive.demo.ProductScenariosDeviceTest com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner
```

결과는 앱 cache의 `product-scenario-<이름>.json`에 저장된다. COMPLETED와 최종 시각을 시험하며 최대 단계·인증·스탬프·쉼터 후보를 별도로 기록한다. 완료 판정은 해당 결과와 별도 결과표를 따른다.

전체 6종 기기 결과와 조건별 결함은 [2026-11-11 결과표](../devlog/2026-11-11-role5-product.md)에 정리했다.
