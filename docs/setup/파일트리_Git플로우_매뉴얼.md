# 깨어있는 드라이브(AwakeDrive) — 파일트리·Git플로우 매뉴얼

작성일: 2026-10-28 · 대상: 팀원 전원(+Claude Code·Codex 등 코딩 에이전트)

> **단일 출처는 [`CLAUDE.md`](../../CLAUDE.md)입니다.** 이 매뉴얼은 그중 "파일이 어디에 있고, Git을 어떻게 쓰는가"만 떼어서 처음 보는 사람도 바로 따라 할 수 있게 풀어 쓴 것입니다. 내용이 다르면 `CLAUDE.md`가 맞고, 이 문서를 고치는 PR을 올립니다.

---

## 1. 저장소 전체 그림

```
sleepissue/
├── CLAUDE.md          # Claude Code가 자동으로 읽는 프로젝트 맥락 (단일 출처)
├── AGENTS.md          # Codex 등 다른 코딩 에이전트가 자동으로 읽는 파일 (CLAUDE.md와 같은 내용)
├── README.md          # 사람이 처음 열어 보는 안내
├── PROGRESS.md         # 담당자별 "지금 상태" 보드
├── docs/
│   ├── plan/           # 상세기획서 v4 두 개 — 기능의 단일 출처
│   ├── proposal/       # 접수 양식
│   ├── setup/          # 개발환경 설치기록, 이 매뉴얼
│   ├── design/         # 화면 시안
│   ├── meetings/       # 회의록
│   ├── ideas/          # 채택 전 아이디어·리서치
│   └── devlog/         # 날짜별 개발 기록
├── src/
│   ├── android/        # Gradle 루트 — Android Studio에서 이 폴더를 연다
│   │   ├── app/        # Pleos SDK·Android에 의존하는 쪽 (pleos/signal/voice/cabin/navi/climate/session/data/ui/demo)
│   │   └── engine/     # 순수 Kotlin/JVM, 판단 로직만 (model/config/fatigue/baseline/pattern/assist/forecast/intervention/rest)
│   └── tools-py/       # 데모 시나리오 합성, 쉼터 CSV 전처리(Python)
├── data/
│   ├── rest_areas/     # 전국졸음쉼터표준데이터 원본·전처리본
│   └── scenarios/      # 데모 시나리오 JSON 6종
├── apk/                # 제출용 APK (git엔 안 올라감)
└── tools/              # inject_crn.sh 등 에뮬레이터 보조 스크립트
```

### 폴더별로 "내가 뭘 여기에 둬야 하나"

| 하려는 일 | 어디에 |
| --- | --- |
| 조향/상태/단계전환 코드 (1번) | `src/android/app/.../signal/`, 순수 규칙은 `src/android/engine/.../intervention/` |
| 졸음 엔진 코드 (2번) | `src/android/engine/.../{fatigue,baseline,pattern,assist}/` |
| 음성·실내 코드 (3번) | `src/android/app/.../{voice,cabin}/` |
| 예보·쉼터·휴식 코드 (4번) | `src/android/app/.../{navi,climate,session,data}/`, 순수 규칙은 `engine/.../{forecast,rest}/` |
| 화면·시연 코드 (5번) | `src/android/app/.../ui/`, 데모 JSON은 `data/scenarios/` |
| 기획 내용 수정 | `docs/plan/` (기능), 아직 확정 안 됐으면 `docs/ideas/` 먼저 |
| 오늘 뭐 했는지 기록 | `docs/devlog/YYYY-MM-DD.md` |
| 회의 결정 사항 | `docs/meetings/YYYY-MM-DD.md` |
| 지금 누가 뭘 맡고 있는지 확인 | `PROGRESS.md` |

---

## 2. Git 플로우

### 2.1 기본 규칙 (CLAUDE.md 11장)

- **`main`에 직접 push 금지.** 항상 브랜치를 만들고 PR을 올려서, 최소 1명이 리뷰한 뒤 merge합니다.
- **절대 커밋하지 않는 것**: CRN, `.env`, API 키, `local.properties`, `*.keystore`, Pleos SDK 바이너리. 커밋 전에 `git status`로 한 번 더 확인하세요.

### 2.2 브랜치 이름

| 역할 | 접두어 | 예시 |
| --- | --- | --- |
| 1번 차량 신호·상태 | `feat/signal-*` | `feat/signal-trip-state` |
| 2번 졸음 엔진 | `feat/engine-*` | `feat/engine-pattern-classifier` |
| 3번 음성·실내 | `feat/voice-*` | `feat/voice-check-manager` |
| 4번 예보·쉼터·휴식 | `feat/rest-*` | `feat/rest-nap-session` |
| 5번 화면·시연 | `feat/ui-*`, `demo/*` | `feat/ui-intervention-screen`, `demo/scenario-drowsy` |
| 문서·저장소 구조 | `docs/*` | `docs/plan-v4` |

### 2.3 작업 순서 (매번 이렇게)

```bash
# 1. main을 최신으로
git switch main
git pull

# 2. 내 작업 브랜치 만들기 (역할 접두어 지키기)
git switch -c feat/engine-fatigue-threshold

# 3. 코드 작성 → 커밋 (무엇을 커밋하는지 git status로 먼저 확인)
git add <바꾼 파일>
git commit -m "조향 급조향 지표 문턱값을 EngineConfig로 분리"

# 4. 원격에 올리기
git push -u origin feat/engine-fatigue-threshold

# 5. GitHub에서 PR 생성 → 팀원 1명 리뷰 요청 → 승인되면 merge
```

- 같은 모듈을 여러 명이 동시에 건드릴 땐 미리 `docs/meetings/`나 단톡방에서 조율하세요. 충돌이 나면 `CLAUDE.md`·기획서 기준으로 어느 쪽이 맞는지 판단하고, 애매하면 올린 사람에게 먼저 물어봅니다.
- 커밋 메시지는 "무엇을 왜 바꿨는지"가 보이면 됩니다. 한국어로 써도 됩니다. 판단 로직(:engine)을 바꿨다면 같은 커밋이나 바로 다음 커밋에 테스트를 같이 넣으세요(`./gradlew :engine:test`).
- PR을 올릴 때 리뷰어 1명을 지정하세요. 작은 수정(오타, 문서)도 리뷰 없이 `main`에 바로 올리지 않습니다.

### 2.4 처음 저장소를 받는 사람(새 팀원·새 기기)이 할 일

1. `git clone git@github.com-junhyeok:sleepissue/sleepissue.git` (또는 팀이 안내한 SSH 별칭)
2. 저장소 루트에 `.env` 생성 (`.env.example` 복사 후 CRN 값 채우기, **절대 커밋 금지**)
3. Android Studio Rabbit 1(2026.2.1) Stable로 `src/android` 폴더 열기
4. Pleos Connect Emulator v2.1.0(API 34) 설치 → Cold boot
5. `tools/inject_crn.sh` 실행해서 CRN 주입 (Wipe Data 했으면 매번 다시)
6. `PROGRESS.md`에서 내 담당 모듈 확인 → 브랜치 만들어서 시작

---

## 3. 자주 하는 실수

- `main`에서 바로 커밋하고 push하려는 것 → 항상 브랜치부터.
- `.env`나 CRN 값을 코드에 하드코딩 → `EngineConfig`/`.env`로만.
- Pleos SDK 메서드를 추측해서 쓰는 것 → 모르면 `TODO("API 확인 필요: ...")`로 남기고 공식 문서·샘플로 확인 후 채우기(CLAUDE.md 7장).
- 기획서에 없는 숫자(문턱값 등)를 코드에 바로 적는 것 → `EngineConfig`에 모으고 `[가설]`이면 주석에 표시.
