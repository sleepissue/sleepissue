# DemoPlayer JSON 작성 가이드


**2026-11-11 제품 연결:** 아래 정규 형식을 유지하면서 팀 원본 `formatVersion/scenarioId/startAt/tMs`도 같은 엄격 파서로 읽는다. `tripPreset {continuousDriveSec,last24hDriveSec}`을 두 형식에서 지원하고, DEMO 전용 과거 운전 구간과 함께 시작한다(0 ≤ 연속 ≤ 최근24시간 ≤ 86400초). 제품은 `data/scenarios` 원본을 APK assets로 직접 배포하므로 기존 재생용 사본의 필드 손실에 의존하지 않는다. [제품 버튼·실제 시험·DMO_005·한계](../devlog/2026-11-11-product-demo-release.md). 이전 ‘미연결/가짜 질문만’ 문단은 당시 구현 범위다.

5번 담당은 이 형식으로 시나리오를 만들고, debug의 **JSON 데모·배속 검증** 화면에서 로드 결과를 확인한다. 실제 차량 입력과 마찬가지로 JSON도 VehicleSignalHub를 거친다. JSON에서 위험 점수·단계·확인 결과를 지정하거나 졸음 엔진을 직접 호출하지 않는다.

최소 예제는 [demo_minimal.json](../../data/scenarios/demo_minimal.json), 구조 정의는 [demo.schema.json](../../data/scenarios/demo.schema.json)이다. 예제는 **0.3초짜리 로더 확인 자료**이며 정상·딴짓·졸음·대화·보조 주행·출발 피로 6종을 대신하지 않는다. 충분히 긴 버튼 검증용 JSON은 debug 앱의 내장 예제를 사용한다.

## SRS와의 관계

기준은 [SRS v0.2](../design/AwakeDrive_SRS_TC_v0.2.xlsx)의 Sys Req Spec·Test Case 152~155행(DMO_001~004)과 Data Interface 75~85행(MSG-11)이다. 원본 Excel은 수정하지 않았다.

- 필수 8종 이름과 초 단위 t, speedSegments의 fromSec/toSec/factor를 유지했다. Sys Req Spec 153행 예시 from/to와 Data Interface 85행 fromSec/toSec가 달라 정식 인터페이스 이름인 후자를 사용한다. from/to 파일은 추측하지 않고 INVALID_SPEED로 거절한다.
- **결측 검증 확장:** SRS 표에서 필수 신호는 nullable=N이지만, 이번 요청의 null 보존과 기존 Hub 계약을 위해 **키는 필수·값은 null 허용**으로 정했다. 정상 시연 6종은 넣을 수 있는 필수 신호를 채운다. null 자료는 미수신·게이트 실패 검증용으로 구별한다.
- gear="OTHER"는 SRS에 있으나 기존 Kotlin Gear에는 없다. 기존 enum을 바꾸지 않고 **null/UNAVAILABLE**로 전달한다. P나 N으로 추정하지 않는다.
- turnSignal="HAZARD"는 기존 Kotlin 모델의 확장이다. SRS의 OFF·LEFT·RIGHT도 그대로 지원한다.
- 선택 power는 ON·OFF·RESUME·UNKNOWN 관측을 넣는 확장이다. 생략/null은 UNKNOWN·UNAVAILABLE이다. OFF 표기는 실제 시동 제어나 앱 종료·Room 복원 시연을 실행하지 않는다.

DMO_005의 저장→앱 종료→재실행+22분 이동, DMO_006의 출발 피로 판정과 6종 완성/E2E는 파일 형식 검증과 별개다.

## 시간과 단위

| 항목 | 규칙 |
| --- | --- |
| schemaVersion | 정수 1 |
| id | 파일명에서 .json을 뺀 이름. 영문·숫자·밑줄·하이픈만 사용 |
| startEpochMs | 시나리오 시작 날짜의 epoch ms, 0 이상의 Long 정수 |
| samples[].t | **시작 후 상대 초**. 첫 값 0, 이후 100ms ±1ms 간격으로 증가 |
| 메모리 시간 | t를 소수 오차 없이 정수 ms로 변환. 0.1초→100ms |
| 종료 | 마지막 tMs +100ms. 마지막 관측도 한 주기 유지 |
| steeringDeg | 조향각 °. 전륜각으로 바꾸지 않음 |
| speedKph·cruise.targetKph | 0 이상의 km/h. 이미 km/h이므로 ×3.6하지 않음 |
| lat·lon | 위도 −90~90°, 경도 −180~180°. 한 쌍 모두 있어야 사용 |
| source | 선택 메타데이터. 적으면 "DEMO"만 허용. 출력은 항상 DEMO |

시각은 **정수 ms로 정확히 표현 가능한 초**를 쓴다. "0.1"처럼 숫자를 문자열로 쓰거나 0.1001초를 쓰면 거절한다. 정수 ms를 t에 넣으면 초로 읽히므로 잘못된 100초 간격이 된다. 작성 시 round(i / 10, 1) 또는 정수 ms에서 만든 소수를 사용한다.

운행/Hub는 상대 시각을 쓰고, 예보는 날짜가 필요하다. DemoClock의 예보용 시간 어댑터가 **startEpochMs + 상대 시나리오 ms**를 기존 ForecastEngine에 제공한다. 예보 결과 epoch ms는 **expectedDrowsyAtMs − startEpochMs**로 상대 운행 시각에 되돌려 연결한다. 기본 예보 시간대는 Asia/Seoul이다. 실제 저장 날짜와 실제 음성 응답 시간은 이 배속 날짜를 사용하지 않는다. 상세 조립 계약은 [DemoClock·Player 가이드](ROLE1_DEMO_PLAYER.md)를 따른다.

종료 epoch가 Long 범위를 넘거나 길이가 DemoClock.MAX_DURATION_MS를 넘으면 INVALID_TIME이다. epoch와 실제 경과 시각을 서로 빼지 않는다.

## 필수 키와 null

각 샘플에는 t, steeringDeg, speedKph, gear, turnSignal, brake, seats, cruise **8개 키가 모두 있어야 한다.** 키 누락은 파일 오류다. 신호가 현재 없다는 뜻은 해당 값을 null로 명시한다. null은 이전 값 유지·0·false·정상 판정이 아니다. 모든 샘플은 완전 관측이며 부분 갱신을 표현하지 않는다.

    {
      "t": 0.1,
      "steeringDeg": 1.1,
      "speedKph": 80,
      "gear": "D",
      "turnSignal": "OFF",
      "brake": false,
      "seats": {
        "driver": true,
        "frontPassenger": false,
        "rearLeft": false,
        "rearCenter": false,
        "rearRight": false
      },
      "cruise": {"active": false, "targetKph": 0},
      "lat": null,
      "lon": null,
      "power": "ON"
    }

seats 객체는 위 5개 키를 모두 적고 좌석별 Boolean/null을 쓴다. seats:null은 착석 전체 미수신이다. cruise 객체는 active와 targetKph를 모두 적는다. 비활성의 알려진 설정 속도가 0이면 0을 쓰고 모르면 null을 쓴다. cruise:null은 두 신호 모두 미수신이다.

좌표는 둘 다 생략하거나 둘 다 null로 둘 수 있다. 한쪽에 값이 있는데 다른 쪽이 없으면 오류다. 값이 있는 DEMO 좌표 쌍은 기존 Hub의 DEMO_LOCATION 예외를 따른다. 좌표 없는 샘플은 기존 REAL 위치로 대체될 수 있으며, 로더가 REAL 좌표를 가져오거나 서로 다른 출처의 위경도를 합치지 않는다.

말소리·녹음·대답 시간·STT 문장은 JSON 필드가 아니다. 기존 debug 화면은 **가짜 질문 이벤트**로 배속 계약을 검증한다. 2026-11-11 제품 데모에는 실제 ON_DEVICE TTS 시작과 확인 종료·실패·취소에 연결한 배속 잠금이 있다. 실제 기기 확인 범위는 [제품 데모 기록](../devlog/2026-11-11-product-demo-release.md), 인계 후 최종 빌드와 과거 근거 구분은 [최종 검증](../devlog/2026-11-11-resume-final.md)을 본다. 사람이 말한 정답률·CabinMonitor 전체 검증을 뜻하지 않는다.

## 배속 구간

speedSegments는 파일 머리에 한 번 둔다. 없거나 null이면 전체 **1배속**이다. 빈 배열은 오류다. 각 구간은 **[fromSec, toSec)**로 앞은 포함하고 뒤는 포함하지 않는다. 0부터 종료까지 **빈틈·중첩 없이 입력 순서대로** 덮어야 한다. 지원 배속은 **1·10·60**뿐이다.

예를 들어 샘플이 0~3599.9초이면 종료는 3600초다.

    "speedSegments": [
      {"fromSec": 0, "toSec": 3300, "factor": 60},
      {"fromSec": 3300, "toSec": 3600, "factor": 1}
    ]

질문 음성 안내가 시작되면 구간과 관계없이 1배속으로 고정하고, 판정·실패·취소로 끝나면 현재 위치가 속한 구간 배속으로 돌아간다. 음성 응답 시간은 elapsedRealtimeMs()로 재며 60으로 나누지 않는다. JSON에 질문 결과를 넣어 이 구간을 건너뛰지 않는다. 실제 음성 연결 여부와 가짜 이벤트 검증 범위는 Player 가이드·개발 기록에서 구분한다.

## 작성과 검증 순서

1. demo_minimal.json을 복사하고 파일명·id·시작 날짜를 정한다. 예제 시작 날짜는 **2026-11-05 16:00:00+09:00**이다.
2. 목적 장면에 필요한 길이만큼 0부터 0.1초 간격 샘플을 만든다. 최소 2개가 필요하다. 값이 같은 연속 샘플도 새 관측으로 각각 적는다.
3. 마지막 샘플 시각+0.1초를 종료 시각으로 계산해 배속 구간을 채운다. 긴 운행 시간만 배속하고 보여 줄 장면에는 1배속을 둔다.
4. UTF-8 JSON으로 저장한다. JSON 주석, 뒤 쉼표, 중복 키, NaN·Infinity와 정의하지 않은 필드는 쓰지 않는다.
5. debug 검증 화면에서 파일 내용을 **JSON 입력에 붙여 넣고 로드**한다. 오류이면 코드·JSON 위치·설명을 보고 고친다. READY이면 재생·배속·질문·Hub 값을 확인한다.
6. 코드 변경 뒤에는 아래 JVM 시험과 APK 빌드로 로더와 연결 회귀를 확인한다.

    cd src/android
    JAVA_HOME=/home/a/.local/opt/android-studio/jbr \
    ANDROID_HOME=/home/a/Android/Sdk \
    ./gradlew :engine:test :app:testDebugUnitTest assembleDebug

구조만 확인하는 JSON Schema 도구는 시간 역행·샘플 간격·구간 연속성·epoch 종료 범위를 보증하지 않는다. **최종 판정은 앱과 JVM 시험이 함께 쓰는 DemoJsonLoader 한 곳**에서 수행한다. 별도 Python 검증 규칙은 추가하지 않았다. SRS의 src/tools-py 검사 경로는 이번 구현의 완료 항목으로 표시하지 않는다.

| 오류 코드 | 의미·예 |
| --- | --- |
| FILE_NOT_FOUND | 지정한 id의 .json 파일이 없음 |
| IO_ERROR | 파일을 읽을 수 없거나 읽기 권한이 없음 |
| INVALID_JSON | 문법·UTF-8 오류, 중복 키, NaN/Infinity, 두 문서를 붙인 파일 |
| INVALID_SCHEMA | 필수 키·자료형·열거 값·좌표 쌍 오류, REAL 출처 또는 미지 필드 |
| INVALID_TIME | 0이 아닌 시작, 역행·중복, 99~101ms 밖 간격, ms 미만 정밀도·epoch 범위 오류 |
| INVALID_SPEED | 1·10·60 외 배속, 잘못된 구간 필드, 빈틈·중첩·역순·미완료 구간 |

파일 Repository는 경로 대신 id를 받는다. ../ 경로나 시나리오 폴더 밖으로 나가는 심볼릭 링크를 허용하지 않고 파일 로더도 .json만 읽는다. 화면·로그에는 오류 코드와 위치를 보여주며 파일 원문이나 외부 예외 원문을 자동으로 기록하지 않는다.
