# 11. 데모 시나리오 형식 제안 (MSG-11, 간트 [7]) — 2번 안

> **상태: 제안.** 1번(DemoPlayer)·5번(시나리오 합성)과 정한다. 간트 [7]은 10/30~11/1이었고 아직 정하지 못했다.
> 바탕: SRS v0.2 Data Interface MSG-11(11칸), REQ_DMO_001~006, 1번 `DemoPlayer` 계약(main), 2번 엔진 동작([05](05-behavior.md)).
> SRS 칸은 그대로 두고, 엔진·예보가 맞게 판정하는 데 필요한 칸만 더했다.

## 1. SRS MSG-11 그대로 쓰면 생기는 일

| 문제 | 영향 | 제안 |
| --- | --- | --- |
| 회전 지점 거리 칸이 없다 | 게이트가 '목적지 없음 → 고속 5분 유지 뒤 계산' 규칙을 쓴다. 모든 시나리오의 첫 5분이 계산되지 않고, 출발 피로가 15:10이 아니라 20:10에 나와 **REQ_DMO_006(15분)에 못 맞춘다** | 행마다 선택 칸 `distToNextTurnM`(m). DemoPlayer가 1초마다 `NaviState`로 낸다 (MSG-14 주기) |
| 시각이 0초부터 센 상대 시각(`t`, Float 초)이다 | 엔진 연결기와 4번 예보는 시각을 epoch ms로 보고 하루 중 시각(취약 시간대·K·예보 말투)을 계산한다. 시나리오 1의 '23:10' 같은 장면이 나오지 않는다 | 파일 머리 `startAt`(ISO 8601, +09:00). 행 시각 = `startAt` + `tMs` |
| `t`가 Float 초다 | 0.1초를 Float로 계속 더하면 오차가 쌓여 100 ms ±1 ms(DMO_001) 검증과 엔진의 시각 순서가 흔들릴 수 있다 | 정수 ms `tMs`. `t`를 유지하면 DemoPlayer가 `round(t × 1000)`으로 바꾼다 |
| 연속 운전·24시간 누적이 시나리오 밖에 있다 | 시나리오 1의 '연속 2시간 10분'(K +0.10)을 보이려면 2시간을 재생해야 한다 | 파일 머리 선택 칸 `tripPreset` — 1번 TripStateStore가 데모 시작 때 이 값으로 시작 (**1번 결정**) |
| 실내 온도·통풍 칸이 없다 | K의 '실내 25 ℃ 이상'(FAT_017)과 조작 공백의 통풍 변경(AST_006)이 데모에서 안 나온다 | 선택 칸 `cabinTempC`, `ventilationLevel` |
| 2차 확장 신호 자리가 없다 | 2차에 형식을 다시 바꿔야 한다 | 예약 칸 `assumed` ([10 7절](10-extension-design.md#7-시나리오-json)). 1차 파서는 모르는 칸을 무시 |

## 2. 제안 형식

```json
{
  "formatVersion": 1,
  "scenarioId": "drowsy",
  "title": "졸음: 15분 정상 → 졸음 추세형",
  "startAt": "2026-11-07T22:55:00+09:00",
  "tripPreset": { "continuousDriveSec": 7800, "last24hDriveSec": 7800 },
  "speedSegments": [
    { "fromSec": 0, "toSec": 900, "factor": 10 },
    { "fromSec": 900, "toSec": 1800, "factor": 1 }
  ],
  "samples": [
    { "tMs": 0, "steeringDeg": -1.0, "speedKph": 98.0, "gear": "D", "turnSignal": "OFF", "brake": false,
      "seats": { "driver": true, "frontPassenger": false, "rearLeft": false, "rearCenter": false, "rearRight": false },
      "cruise": { "active": false, "targetKph": null },
      "distToNextTurnM": 12000.0, "cabinTempC": 23.5 },
    { "tMs": 100, "steeringDeg": -0.9, "speedKph": 98.0, "gear": "D", "turnSignal": "OFF", "brake": false,
      "seats": { "driver": true, "frontPassenger": false, "rearLeft": false, "rearCenter": false, "rearRight": false },
      "cruise": { "active": false, "targetKph": null },
      "distToNextTurnM": 11997.3, "cabinTempC": 23.5 }
  ]
}
```

- 10 Hz 그대로다(DMO_001). 30분 시나리오는 18,000행, 행마다 약 250바이트라 파일 하나가 4~5 MB쯤이다 [추정]. APK에 6개를 넣기에 크면 gzip으로 묶는 것을 1·5번이 정한다.
- 파일 위치: `data/scenarios/<scenarioId>.json` (CLAUDE.md 10장). 앱 assets로 옮기는 방법은 1·5번이 정한다.

## 3. 칸 정의

### 파일 머리

| 칸 | 형 | 필수 | 뜻 | 근거 |
| --- | --- | --- | --- | --- |
| `formatVersion` | Int | Y | 형식 판. 지금 1 | 형식이 바뀌어도 옛 파일을 구분 |
| `scenarioId` | String | Y | `normal`·`distraction`·`drowsy`·`talk`·`assist`·`departure` | 1번 `DemoCommand.Load(scenarioId)` |
| `title` | String | N | 화면 표시용 | — |
| `startAt` | String(ISO 8601) | Y | 첫 행의 시각 (현지, +09:00) | 연결기·예보의 하루 중 시각 |
| `tripPreset` | `{continuousDriveSec, last24hDriveSec}` | N | 데모 시작 때 운행 상태 | 1번 결정 |
| `speedSegments` | `[{fromSec, toSec, factor}]` | N | 구간별 배속 1·10·60 | SRS 그대로 (DMO_002) |

### 행 (`samples`, 100 ms마다)

| 칸 | 형 | 필수 | 단위 | SRS | 엔진에서 쓰는 곳 |
| --- | --- | --- | --- | --- | --- |
| `tMs` | Long | Y | ms | `t`(Float 초)를 바꿈 | 모든 시간 창 |
| `steeringDeg` | Float | Y | ° 운전대각 (전륜각 아님), 왼쪽 − [해석] | 그대로 | 지표 3개. 엔진은 부호와 상관없이 변화량만 씀 |
| `speedKph` | Float | Y | km/h | 그대로 | 게이트 60 km/h |
| `gear` | `P·R·N·D` | Y | — | SRS의 OTHER는 엔진 모델에 없음 → 빼거나 null | 게이트 D단 |
| `turnSignal` | `OFF·LEFT·RIGHT·HAZARD` | Y | — | HAZARD 추가 (엔진 모델에 있음) | 게이트 앞뒤 10초 |
| `brake` | Boolean | Y | — | 그대로 | 게이트 앞뒤 5초 |
| `seats` | `{driver, frontPassenger, rearLeft, rearCenter, rearRight}` | Y | — | 그대로 (칸 이름은 1번 `SeatOccupancy`) | K 혼자 운전 |
| `cruise` | `{active, targetKph}` | Y | km/h | 그대로 | 보조 주행·조작 공백 |
| `lat`·`lon` | Double | N | ° | 그대로 | 쉼터(4번) |
| `distToNextTurnM` | Float | N | m | **추가** | 게이트 300 m, 없으면 5분 규칙 |
| `cabinTempC` | Float | N | ℃ | **추가** | K 실내 25 ℃ |
| `ventilationLevel` | Int | N | 단계 | **추가** | 조작 공백 |
| `assumed` | object | N | — | **예약** (2차) | [10](10-extension-design.md) |

- 선택 칸이 빠지면 null이다. null을 0이나 false로 바꾸지 않는다 (1번 `SignalFrame` 규칙과 같음).
- 말소리는 칸이 없다. 실제 마이크로 받는다 (SRS MSG-11 비고).

## 4. 시나리오 6종에서 2번 엔진이 내는 것

시나리오를 만든 뒤 아래가 나오는지 보면 된다. 시각은 시나리오 시간, 결과는 넣은 프레임보다 10초 늦게 나온다. 자세한 수치는 [05](05-behavior.md).

| `scenarioId` | 조향 | 꼭 넣을 것 | 2번 결과 |
| --- | --- | --- | --- |
| `normal` | 미세 수정(0.5~3° 방향 전환) 분당 20회 이상, 큰 수정(5° 이상) 분당 4회 이하 | `distToNextTurnM` | 전 구간 평활 < 40 (PRD_002) |
| `drowsy` | 15분 정상 → 미세 0~6/분, 큰 수정 8/분 이상, 2~3분마다 멈춤 뒤 급조향 | `startAt` 23:00 전후, `tripPreset` 2시간 이상, 혼자 | 졸음 시작 + 6분 10초 추세형, 급조향을 섞으면 + 4분 평활 60, + 6분 평활 80 |
| `distraction` | 15분 10초 뒤 35초 그대로 → 1초 안 7° 이상, 그 밖은 정상 | 멈춤 18초 이상 | 급조향 + 10초 `RAPID_STEERING`, 1~2분 뒤 튐형. 시작 시각 상관없음(11/7 고침) |
| `talk` | 정상과 같게 | (3번 실내 감지 몫) | 2번 결과는 `normal`과 같음 |
| `assist` | `cruise.active = true` 구간, 해제 뒤 30초 안 급조향 | `cruise.targetKph` | 크루즈 중 계산 불가(회색), 해제 뒤 `HANDOVER_UNSAFE` |
| `departure` | 처음부터 미세 −30% 이상과 큰 1.5배 이상 (또는 둘 중 하나 + 급조향 1회) | **`distToNextTurnM`** | 15:10 출발 피로·민감 모드 (DMO_006). 거리 칸이 없으면 20:10 |

- 출발 피로 판정은 게이트 통과 15분 + 확정 지연 10초라 15:10이다. DMO_006 합격기준 '15 min 시점'에 10초를 허용할지 팀장과 정한다.
- 기본 기준선은 미세 20/분·큰 4/분 [가정]이다. 정상 조향이 이보다 훨씬 많으면 처음 15분 판정이 무뎌진다 ([05 C2](05-behavior.md#c2-딴짓-오늘의-나-확정-전)).

## 5. 확인 방법

- 5번 합성 스크립트(`src/tools-py/`)가 파일을 만들면, 2번이 엔진 시뮬레이터로 파일마다 위 표의 결과가 나오는지 확인한다 ([05](05-behavior.md)을 만든 방식).
- 형식이 정해지면 시나리오 파일 하나를 읽어 엔진에 넣는 시험을 `:engine` 시험에 더할 수 있다 [제안]. 파일이 바뀌어 기대 결과가 깨지면 바로 알 수 있다.

## 6. 정할 것

| # | 안건 | 2번 제안 | 누가 |
| --- | --- | --- | --- |
| 1 | `t`(Float 초) → `tMs`(정수 ms) | 바꾼다 | 1·5번 |
| 2 | `distToNextTurnM` 칸 | 넣는다 (DMO_006) | 1·5번 |
| 3 | `startAt` | 넣는다 (시각 맥락·예보) | 1·4·5번 |
| 4 | `tripPreset` | 1번 TripStateStore가 받을 수 있으면 넣는다 | 1번 |
| 5 | `cabinTempC`·`ventilationLevel` | 선택 칸으로 넣는다 | 1·5번 |
| 6 | `assumed` 예약, 모르는 칸 무시 | 넣는다 (2차 대비, 비용 없음) | 1번 |
| 7 | `gear`의 OTHER, `turnSignal`의 HAZARD | 엔진 모델(`Gear`·`TurnSignal`)에 맞춘다 | 팀장(SRS) |
| 8 | DMO_006의 10초 | '15분 + 확정 지연 10초'로 적는다 | 팀장(SRS) |
