# 1번 개입 정책 연결 가이드 — SRS v0.2

`InterventionPolicy`는 단계 규칙을 계산하는 순수 Kotlin 코드이고, `PolicyInterventionManager`는 결과를 외부 실행 요청으로 연결한다. 요청 통로는 ROS의 명령 토픽과 비슷하며, 요청을 보냈다고 음성·내비 실행이 완료된 것은 아니다. 실제 Activity·서비스의 조립 지점은 아직 없다.

## 적용 기준과 생성 예시

기준은 원본 `AwakeDrive_SRS_TC_v0.2.xlsx`의 `Sys Req Spec`·`Test Case` INT_004(36행), INT_006(38행), INT_008(40행), INT_015(191행)이다. 원본은 수정하지 않았다.

```kotlin
val config = EngineConfig()
val policy = InterventionPolicy(config, FirstEscalation.IMMEDIATE)
val manager = PolicyInterventionManager(
    policy = policy,
    config = config,
    clock = clock,
    sessionSource = Source.REAL,
    timerBasis = RequestTimerBasis.ELAPSED_REALTIME,
    newRequestId = newRequestId,
)
```

`clock`과 중복되지 않는 ID 생성기 `newRequestId`는 소유자가 주입한다. `REAL`은 Playground SDK·Virtual Console 입력이다. `requests`를 담당자 한 곳에서 구독한 다음 `start`하고 입력을 `submit`한다. 소유자는 `Tick`을 공급하고 종료 시 `stop`한다. 질문은 접수 상태가 아닌 실제 안내 시작 때 같은 ID로 `STARTED`를 보내고, 최종 `CheckResult`를 전달한다. 요청의 시작·결과·취소는 같은 ID와 문맥을 사용한다.

INT_004 적용 시 최초 일반 상승은 **IMMEDIATE**다. 생성자의 기본값도 IMMEDIATE이며, 명시 인자를 쓰는 조립 코드도 같은 값을 사용한다. `AFTER_INTERVAL`은 기존 비교 시험용으로 남겼으며 v0.2 제품 조립에는 사용하지 않는다. R=90+추세형 입력은 1단계 t=0, 2단계 t=30초, 3단계 t=60초다. 이후 일반 상승은 30초에 한 칸이고, 느림 2회·무응답·REST 확인 결과와 반복 급조향 안전 우회는 다음 30초 평가까지 기다리지 않는다. 실제 경고음 시작 지연은 외부 음성 모듈 연결 후 별도로 측정해야 한다.

## 휴식 인증과 현재 조건 재평가

INT_006의 시간·예보로 유지된 1단계와 INT_008의 3단계 해제 조건을 구분한다.

- 같은 세션 출처의 새 `RestCertified`를 받으면 과거 맥락의 `cautionHeldUntilRest`를 해제하고 현재 점수·취약 시간대·확인 조건을 다시 평가한다. 조건이 없으면 10분의 저점수 대기나 정상 회복 확인 없이 0단계로 내려간다.
- 현재 점수가 정상 모드 40/민감 모드 30 이상이면 1단계를 유지할 수 있다. 단계가 같아도 표시 사유는 현재 조건으로 갱신한다. 2단계 조건이 남으면 일반 상승 간격에 따라 올라간다. 인증을 받았다는 이유만으로 점수나 안전 조건을 지우지 않는다.
- 점수만으로 진입한 1단계의 하강은 INT_005의 10분 규칙을 유지한다. 미평가 입력을 점수 0이나 정상 판정으로 바꾸지 않는다.
- Manager는 인증 전에 보관한 `trip`·`forecast` 입력을 만료시킨다. 이것은 실제 TripStateStore·예보 엔진의 초기화 완료를 뜻하지 않는다. 소유 모듈은 휴식 후의 최신 운전 시간·재계산된 예보를 다시 공급해야 한다. 새로 공급한 현재 조건은 다시 평가한다.
- 순수 정책을 직접 호출할 때는 인증 평가에만 `restCertified=true`를 보내고, 시간·예보도 휴식 후 현재 값으로 함께 전달한다. 현재 값으로 다시 문턱을 충족하면 유지 조건이 다시 생긴다.
- **3단계는 인증만으로 내려가지 않는다.** 유효한 인증 세션의 RECOVERY 질문이 실제 시작된 뒤 정상 회복 결과가 와야 해제된다. 느림·무응답·장치 실패·졸림 의도는 정상 회복이 아니다.
- 다른 출처·중복·이미 처리한 과거 인증은 새 해제 동작을 만들지 않는다. 정상 회복 후에는 `RestRecoveryResetRequest`를 발행하지만, RES_011의 실제 연속 시간·평활값 초기화와 예보 재계산은 별도 담당 연결이 남아 있다.

## INT_015는 미확정

INT_015는 팀 미확정 **Should·제안**이다. 기존 `suggestRestAtCheck` 선택 인자와 2단계 진입 시 검색·안내 경로를 제거했다. 2단계 확인 결과가 정상 또는 느림이어도 이 제안에 따른 검색·음성·카드를 활성화하지 않는다. 취소된 1단계 검색 결과로 2단계에서 안내하지도 않는다. 1단계 INT_014와 3단계 쉼터 절차는 유지한다. INT_015를 필수 구현 완료 항목으로 집계하지 않는다.

## 실행한 검증과 한계

2026-11-02에 `:engine:test`·`:app:testDebugUnitTest`·`assembleDebug`를 실행해 성공했다. engine 61개, app 69개(템플릿 1개 포함), 합계 130개이며 실패·오류·건너뜀은 0개다. 이번 보완의 신규 시험은 engine 6개·app 7개다.

- [InterventionPolicyTest](../../src/android/engine/src/test/java/com/sleepissue/awakedrive/engine/intervention/InterventionPolicyTest.kt): 최초 즉시 상승, 29.999/30초·59.999/60초, 시간·예보 인증 해제, 40/30 경계, 현재 맥락·확인·안전 우선, 점수 단독 하강 회귀.
- [PolicyInterventionManagerTest](../../src/android/app/src/test/java/com/sleepissue/awakedrive/signal/PolicyInterventionManagerTest.kt): 30분 재안내 경계, 인증 전 캐시 만료·새 입력 재평가, 출처·중복 인증, 인증+회복, INT_015 비활성 및 취소된 검색 결과 차단.

이번 결과는 JVM 정책·요청 관리와 APK 빌드 결과다. Playground SDK 수신·실제 음성·경유지 실행·Room·서비스 시험을 통과한 것으로 보지 않는다. 이미 추가된 경유지 제거는 기존 `NOT_SUPPORTED` 상태이며 이번 범위에서 바꾸지 않았다. 상세 실행 기록은 [개발 기록](../devlog/2026-11-02.md)을 본다.
