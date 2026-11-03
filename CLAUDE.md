# CLAUDE.md — sleepissue (깨어있는 드라이브 / AwakeDrive)

Claude Code가 이 저장소에서 작업할 때 읽는 프로젝트 맥락이다.
답변과 주석은 한국어, 코드 식별자는 영어로 쓴다.

> **`AGENTS.md`는 이 파일과 내용이 같다.** Codex 등 Claude Code가 아닌 코딩 에이전트는 `CLAUDE.md`를 자동으로 읽지 않고 `AGENTS.md`를 읽으므로 둘을 만들어 뒀다. **이 파일을 고치면 `AGENTS.md`도 똑같이 고친다.**
>
> 파일트리·Git 플로우를 사람이 보기 편하게 풀어 쓴 매뉴얼(PDF 포함)은 [`docs/setup/파일트리_Git플로우_매뉴얼.md`](docs/setup/파일트리_Git플로우_매뉴얼.md). 10장·11장의 요약판이다.

**기획의 단일 출처(Source of Truth)는 `docs/plan/` 의 기획서 두 개다.** 이 파일과 기획서가 다르면 기획서를 따르고, 이 파일을 고치는 PR을 올린다.

- `docs/plan/깨어있는드라이브_상세기획서_v4_현재기능.md` — **지금 구현 대상.** 지금 쓸 수 있거나 호출해 볼 수 있는 기능만 사용
- `docs/plan/깨어있는드라이브_상세기획서_v4_확장가정.md` — 10/28 설명회에서 LFA·LKA·핸즈온·DAW·진동이 열린다고 가정한 버전. 15장이 설명회 질문지

---

## 1. 제품 한 줄

졸음운전을 경고에서 끝내지 않고 **차가 휴식까지 이어 주는** Pleos Connect 앱.

| 단계 | 기능 |
|---|---|
| A. 출발 전 예보 | 출발 시각·남은 시간·오늘 누적 운전·취약 시간대·개인 졸음 시점으로 졸릴 시점과 휴식 후보 쉼터를 TTS로 예보 |
| 주행 중 | **조향 패턴**으로 졸음 징후 감지 → 음성(대답까지 걸린 시간) 또는 동승자에게 확인 → 0~3단계 개입 → 진행 방향 앞 졸음쉼터를 **내비 경유지로 추가** |
| B. 파워냅 | 정차 중 운전자가 원할 때만 15분 알람 + 회복 확인. 앱은 잤는지 판단하지 않음 |
| C. 휴식 인증·보상 | 쉼터 반경 150 m + P단(또는 시동 꺼짐) + 15분을 차량 데이터로 자동 인증 → 스탬프. 시동을 껐다 켜도 끊김 없음 |

- **포지셔닝:** 진단 아님. 차량 기본 경고(DAW)를 대신하지 않는 위험 신호 알림·대응 보조 서비스.
- **핵심 원칙 3가지** (기획서 4-1)
  1. 맥락(시간·거리·시간대)은 **판정이 아니라 민감도**만 바꾼다
  2. 조향은 **게이트를 통과한 구간에서만** 계산한다
  3. 의심되면 **사람에게 확인**한다. 애매하면 덜 묻는 쪽이 아니라 더 묻는 쪽으로 틀린다

## 2. 일정·평가

| 날짜 | 할 일 |
|---|---|
| 10/28 | 설명회 (확장 가정 문서 15장 질문지) |
| 10/29~11/1 | 에뮬레이터 신호·API 동작 확인, 데모 시나리오 형식 확정, 화면 시안 착수 |
| 11/2~11/9 | 핵심 구현 |
| 11/5 | 화면 시안 3장(주행·단계 알림·휴식) |
| **11/10** | **MVP 동결** (이후 새 기능 금지, 버그 수정만) |
| 11/11~11/14 | 시연 영상(2분 30초)·README·APK·접수 양식 → **11/14 제출** (마감 11/15 23:59) |
| 11/23 | 1차 결과 |
| 11/16~12/2 | 평소의 나·개인 예보·리포트·휴게소 데이터·설명회에서 열린 기능 |
| 12/3~12/4 | 2차 PT |

심사 기준: 차량특화성, 창의성, 기술활용성(Pleos SDK/API), 사업성.
1차(11/15)는 모든 기능의 **기본 동작** MVP, 2차(12/3)는 개인화·리포트·정확도. 기능별 1·2차 범위는 기획서 3장 표를 따른다.

## 3. 팀·역할 (기획서 12장)

5명, 로봇공학 전공. Kotlin 경험자 2명이 1·2번. 나머지는 Android 입문자이므로 새 개념(생명주기, 코루틴·Flow, Compose, 포그라운드 서비스)은 한두 줄 설명을 덧붙인다. ROS 비유(노드·토픽·콜백)가 잘 통한다.

| 역할 | 모듈 | 브랜치 접두어 |
|---|---|---|
| 1번 차량 신호·상태 | VehicleSignalHub, TripStateStore, InterventionManager, DemoPlayer | `feat/signal-*` |
| 2번 졸음 엔진 | FatigueEngine, BaselineStore, PatternClassifier, AssistModeDetector | `feat/engine-*` |
| 3번 음성·실내 | VoiceAgent, SpeechAgent, CabinMonitor, CheckManager | `feat/voice-*` |
| 4번 예보·쉼터·휴식 | ForecastEngine, RestAreaRepository, NaviBridge, ClimateController, RestSession, NapSession, RewardLedger | `feat/rest-*` |
| 5번 화면·시연 | Compose UI, 데모 시나리오 JSON 6종, 테스트, 영상·README | `feat/ui-*`, `demo/*` |

## 4. 아키텍처

한 방향 흐름: **신호 수집 → 판단 → 개입 결정 → 음성·내비·화면 실행.**
MVP는 **서버 없음**(모든 처리가 차 안). 데모 재생도 실제 신호와 **같은 VehicleSignalHub**로 들어가 같은 판단 로직을 거친다. 재생 중 Virtual Console 입력이 들어오면 실제 값이 우선한다.

### 설계 규칙
- **판단 로직은 Android에 의존하지 않는 순수 Kotlin 모듈(`:engine`)에 둔다.** FatigueEngine, BaselineStore, PatternClassifier, AssistModeDetector, ForecastEngine, 단계 전환 규칙, 휴식 인증·보상 규칙이 대상. `./gradlew :engine:test`로 에뮬레이터 없이 검증
- 시간은 `Clock` 인터페이스로 주입한다 (데모 배속 1·10·60배, 시동 꺼짐 재현, 단위 테스트를 위해). `System.currentTimeMillis()` 직접 호출 금지
- **기획서의 모든 숫자 문턱값은 `EngineConfig` 한 곳에 모은다.** 하드코딩 금지. 기획서에 "초깃값이며 시연 데이터로 조정"이라고 되어 있으므로 바꾸기 쉬워야 한다. `[가설]` 표시 값은 주석에 `// [가설]`로 남긴다
- SDK 호출은 `app/.../pleos/` 래퍼 뒤로 숨기고, 엔진에는 정규화된 신호 묶음만 넘긴다
- 모든 Vehicle API는 `checkXXXCapabilities`로 먼저 확인하고, 미지원이면 해당 지표·기능을 끄고 대체 동작으로 간다

### 신호 묶음 (10 Hz, 초안 — 1·2번이 1주차에 확정)

```kotlin
data class SignalFrame(
    val tMs: Long,
    val steeringDeg: Float?,      // SteeringWheel
    val speedKph: Float?,         // PERF_VEHICLE_SPEED
    val gear: Gear?,              // GEAR_SELECTION
    val turnSignal: TurnSignal?,  // TurnSignal
    val brakeOn: Boolean?,        // Brake
    val cruiseActive: Boolean?,   // DrivingMode
    val cruiseTargetKph: Float?,
    val seats: SeatOccupancy?,    // Seat
    val ventilationLevel: Int?,   // Seat HVAC 통풍 상태
    val cabinTempC: Float?,       // HVAC
    val distToNextTurnM: Float?,  // NaviHelper TBT
    val source: Source            // REAL / DEMO
)
```

null은 "이 신호를 지금 못 받음"을 뜻하며, 엔진은 null을 게이트 불통과로 처리한다.

## 5. 핵심 판단 규칙 요약 (상세·근거는 기획서 4~7장)

- **조향 게이트:** 시속 60 km 이상 + D단 + 방향지시등 전후 10초·제동 전후 5초 아님 + 다음 회전 지점 300 m 이상 + 보조 주행 모드 아님
- **조향 지표 3개** (1분 창, 30초 갱신): 미세 수정(0.5~3° 방향 전환, 졸리면 감소), 큰 수정(5° 이상, 증가), 멈춤 뒤 급조향(4초 넘게 0.5° 미만 변화 → 1초 안에 6° 이상)
- **위험 점수:** `B = 100 × (0.35·f미세 + 0.25·f큰 + 0.40·f급조향)`, `R = min(100, B × K)`, 3분 지수평균. K는 맥락 가중(최대 1.35). **B = 0이면 맥락이 높아도 R = 0**
- **기준선 2개:** 평소의 나(깨어 있던 구간 중앙값, 최근 10회, 3회 전엔 기본값) / 오늘의 나(오늘 유효 구간 처음 15분). 출발 피로면 평소의 나로 고정 + 민감 모드(문턱 30/50/70)
- **딴짓 vs 졸음:** 최근 10분 중 이상 분 6분 이상 = 추세형(졸음 쪽), 3분 이하 + 매번 1분 안 회복 = 튐형(딴짓 쪽), 4~5분 = 음성 확인으로 결정
- **보조 주행 모드:** 크루즈 작동 또는 조향이 기계처럼 매끈함 → 조향을 판단에서 빼고 정기 확인(20분, 위험 맥락이면 10분)·조작 공백(15분)·인계 품질로 판단
- **단계:** 0 평상 / 1 주의(안내만, 질문 없음) / 2 확인(음성·동승자 확인 + 사전 동의 시 운전석 통풍) / 3 휴식 유도(쉼터 경유지 제안, 5초 무응답 시 10초 뒤 자동 추가, [취소] 가능). 단계는 30초에 한 칸씩만 오르며, 확인 결과·안전 우회(5분 안 급조향 3회)는 즉시 3단계
- **음성 확인 판정은 "신호음 → 목소리 시작" 시간으로만 한다.** STT 결과는 다음 행동(쉴게·잘게·응·아니)을 정하는 데만 쓴다. 느림 = 기준의 1.5배 또는 2초 초과, 무응답 = 5초
- **시동 꺼짐:** 15분 미만 = 같은 운행 / 15분 이상 + 쉼터 = 휴식 인증 / 15분 이상 + 다른 곳 = 연속 운전 0, 보상 없음 / 2시간 초과 = 새 운행. 10초마다 상태 저장

## 6. 개발 환경 (팀 전원 통일)

- Android Studio **Rabbit 1 (2026.2.1) Stable** — Canary 금지, AGP 업그레이드 제안은 합의 전 수락 금지
- **Pleos Connect Emulator v2.1.0**, API 34, Automotive, **Cold boot 필수**, RAM 4 GB
- Virtual Console: `adb forward tcp:8088 tcp:8088`
- Kotlin, Jetpack Compose, Coroutines·Flow, Room. `minSdk 28`
- 에뮬레이터 이미지 저장소: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` (구 `connect.v2.0.5` 경로는 404)
- Pleos Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/`
- 의존성 (기획서 9장 기준): `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1`
- **TTS·STT는 반드시 `Mode.ON_DEVICE`** (생략하면 서버 우선 HYBRID)
- 공식 Vehicle 샘플: https://github.com/Pleos-Playground/pleos-sample-vehicle-app

### CRN 주입 (에뮬레이터마다 1회, Wipe 시 재주입)

```bash
adb root
adb shell su 0 "echo 'propId: 554696961 areaId: 0 values: {CRN}' > /data/vendor/vsomeip/vhal_fifo"
adb reboot
```

CRN은 `.env`에서 읽는다(`tools/inject_crn.sh`). 코드·커밋·로그에 절대 넣지 않는다.

## 7. SDK 사용 상태 (기획서 9장 요약)

| 상태 | 기능 |
|---|---|
| 바로 사용 | Vehicle 조회·구독: SteeringWheel, TurnSignal, Brake, DrivingMode(크루즈 상태·설정 속도), Seat(착석·통풍 상태), HVAC 조회, Odometer(트립 시간) / `CarPropertyManager`: `PERF_VEHICLE_SPEED`, `GEAR_SELECTION` / NaviHelper: `getCurrentLocationInfo`, `getDestinationInfo`, `getTBTInfo`, `addWaypoint`, `requestRouteWithWaypoints`, `requestNavigationScreen` / `AudioRecord`, `BOOT_COMPLETED`, `CarUxRestrictionsManager`, 오디오 포커스 |
| 호출해 보고 안 되면 대체 | `setHvacSeatVentilation`(호출 뒤 **다시 읽어 반영 확인**, 안 되면 "통풍을 켜 보세요" 음성) / TTS·STT `ON_DEVICE`(안 되면 녹음 음성 + 대답 시간 + 버튼) |
| 지금 불가 | LFA·LKA 상태, 핸즈온, 차량 DAW·주의 분산 경고, 시트·핸들 진동 → 확장 가정 버전에서만 |

권한 후보: `NAVI_ROUTE_SEARCH`, `android.car.permission.CAR_SPEED`·`CAR_POWERTRAIN`, `RECORD_AUDIO`·`FOREGROUND_SERVICE_MICROPHONE`, `RECEIVE_BOOT_COMPLETED`, `pleos.car.permission.TTS_SERVICE`·`STT_SERVICE`, Vehicle 조회(`READ_CAR_STEERING`, `CAR_DRIVE`, `CAR_MILEAGE`, `CAR_SEATS`, `CAR_CLIMATE`, `READ_ADAS_STATES`), 제어(`CONTROL_CAR_SEATS`). **최종 목록은 각 API의 `@RequiresPermission`으로 확정한다.**

### ⚠️ Pleos SDK 코드 작성 규칙
- Pleos SDK는 최신 독점 SDK라 학습 데이터에 거의 없다. **기획서와 공식 문서·공식 샘플에 없는 클래스·메서드·파라미터를 추측해서 쓰지 않는다.** 모르면 `TODO("API 확인 필요: ...")`로 남긴다
- 위 표의 메서드 이름도 **기획서 기준**이므로, 구현 전에 API Reference(document.pleos.ai)와 공식 샘플로 시그니처를 확인한다
- 공식 문서의 Gradle 예제를 통째로 복사하지 말고, 템플릿 구조를 유지한 채 저장소·의존성 줄만 추가한다 (AGP 9.x 충돌 방지)

## 8. Android·차량 앱 규칙

- 주행 중 화면 제한: `CarUxRestrictionsManager` 리스너로 제한 중 리포트·설정·데모 화면을 잠근다
- 음악과 겹치는 안내·말소리 감지 중에는 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
- 모든 음성 앞에 150~200 ms 무음
- 마이크 포그라운드 서비스는 앱 화면이 떠 있을 때 시작해야 한다(백그라운드 신규 시작 불가). 부팅 직후엔 화면이 뜰 때까지 경고음·화면 알림으로 대신
- 주행 중에는 화면 조작 없이 말로 대답·동의. 3단계 [취소]와 동승자 버튼만 보조로 남김
- 같은 단계 알림 10분 내 재알림 금지(3단계 재안내 예외)

## 9. 개인정보·안전

- 음성 원본 저장 금지. 말소리 유무와 대답 시간 숫자만 남긴다. STT 결과도 짧은 대답의 뜻만 쓰고 문장은 저장하지 않는다
- 기록은 차 안(Room)에만, 설정에서 삭제 가능
- 통풍은 사전 동의한 경우만, 운전석만, 바꾸기 전 값을 저장했다가 휴식 인증 뒤 되돌린다. 창문은 열지 않는다
- 데모 재생 중에는 화면 구석에 '데모 재생 중' 표시

## 10. 저장소 구조

```
sleepissue/
├── CLAUDE.md
├── AGENTS.md        # 이 파일과 내용 동일 (Codex 등 다른 에이전트용)
├── README.md
├── PROGRESS.md      # 담당자별 지금 상태 (역할 자체는 3장이 단일 출처)
├── docs/
│   ├── plan/        # 상세기획서 v4 두 개 (단일 출처)
│   ├── proposal/    # 접수 양식
│   ├── setup/       # 개발환경 설치기록, 파일트리·Git플로우 매뉴얼
│   ├── design/      # 화면 시안
│   ├── meetings/
│   ├── ideas/       # 아직 기획서에 들어가지 않은 아이디어·리서치 (CLAUDE.md 밖, 팀 운영용)
│   └── devlog/      # 날짜별 개발 기록 (CLAUDE.md 밖, 팀 운영용)
├── src/
│   ├── android/     # Gradle 루트 — Android Studio에서 이 폴더를 연다
│   │   ├── settings.gradle.kts         # include(":app", ":engine")
│   │   ├── gradle/libs.versions.toml
│   │   ├── engine/  # 순수 Kotlin/JVM: model, config, fatigue, baseline, pattern, assist, forecast, intervention, rest
│   │   └── app/     # pleos/(SDK 래퍼) signal/ voice/ cabin/ navi/ climate/ session/ data/(Room) ui/ demo/
│   └── tools-py/    # 데모 시나리오 신호 합성·검증(Python), 쉼터 CSV 전처리
├── data/
│   ├── rest_areas/  # 한국도로공사 전국졸음쉼터표준데이터(253곳) 원본·전처리본
│   └── scenarios/   # 데모 시나리오 JSON 6종: 정상·딴짓·졸음·대화·보조주행·출발피로 (10 Hz)
├── apk/             # 팀명_AwakeDrive_release.apk (제출용, git엔 안 올라감)
└── tools/           # inject_crn.sh(완성), vhal_set.sh 등
```

## 11. Git·보안

- `main` 직접 push 금지 → 브랜치 + PR + 1인 리뷰 후 merge
- 커밋 금지: CRN, `.env`, API 키, `local.properties`, `*.keystore`, Pleos SDK 바이너리
- 라이선스는 공모전 약관(지식재산권) 확인 전까지 추가하지 않음
- 원격: `git@github.com:sleepissue/sleepissue.git` (SSH)
- 시연 녹화는 OBS(화면 + 시스템 오디오). `adb shell screenrecord`는 소리를 녹음하지 않음

## 12. Claude Code 작업 방식

- 변경 전에 관련 파일과 해당 기획서 절을 먼저 읽고, 계획을 짧게 말한 뒤 수정한다
- 판단 로직을 바꾸면 `:engine` 단위 테스트를 같이 추가한다. 기획서의 시나리오(예: 23:10 추세형 64점, "네" 2.3초·2.6초 → 3단계)를 테스트 케이스로 옮기면 좋다
- 빌드 확인: `cd src/android && ./gradlew :engine:test` / `./gradlew assembleDebug`
- 수치·주장은 근거를 함께 제시하고 가정은 `[가정]` 또는 `[가설]`로 표시한다
