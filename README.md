# 깨어있는 드라이브 (AwakeDrive)

졸음운전을 경고에서 끝내지 않고, 쉼터 경유지 안내와 휴식 인증·보상까지 이어 주는 Pleos Connect 앱입니다.

> **현재 상태(2026-11-12):** 1번 후속 코드의 앱461개 시험과manifest 처리가 통과했습니다. 엔진351개는 같은 날 앞 실행 근거를 재사용했습니다. 최신main의5번 제품UI(PR20)는 이 브랜치와 아직 통합 전입니다. [로컬 구현·데모/완성품의 남은 작업](docs/setup/ROLE1_DEMO_PRODUCT_STATUS.md)을 먼저 확인하세요. **사용자 지시로 APK 생성·서명·설치는 중단 중**이며 아래 명령/unsigned 파일 설명은 이전 작업 기록입니다. 현재 소스의 최신 제출용 APK를 뜻하지 않습니다.

## 바로 볼 것

- **프로젝트 전체 맥락(기획·일정·아키텍처·규칙)**: [`CLAUDE.md`](CLAUDE.md) (Codex 등 다른 코딩 에이전트는 같은 내용의 [`AGENTS.md`](AGENTS.md) 참고)
- **파일트리·Git플로우 매뉴얼(처음 합류하는 사람용, PDF 포함)**: [`docs/setup/파일트리_Git플로우_매뉴얼.md`](docs/setup/파일트리_Git플로우_매뉴얼.md)
- **지금 누가 뭘 하는지**: [`PROGRESS.md`](PROGRESS.md)
- **2번 졸음 판단 엔진 설명·팀원별 할 일**: [`docs/fatigue-engine/README.md`](docs/fatigue-engine/README.md)
- **Pleos 메일 답변 (11/5, ADAS SDK 불가·조향 1 Hz)**: [`docs/meetings/2026-11-05_Pleos_메일답변.md`](docs/meetings/2026-11-05_Pleos_메일답변.md)
- **문서 폴더 구조**: [`docs/README.md`](docs/README.md)
- **Android 프로젝트 구조**: [`src/android/README.md`](src/android/README.md)
- **Playground SDK 실시간 검증·Virtual Console 조작**: [`docs/setup/ROLE1_PLAYGROUND_SDK.md`](docs/setup/ROLE1_PLAYGROUND_SDK.md)

## 빌드·설치·제품 데모 (2026-11-12)

Android Studio에서는 `src/android`를 엽니다. JDK는 설치한 Android Studio의 JBR, Android SDK와 Pleos Maven 의존성이 필요합니다. `local.properties`·SDK 바이너리·키는 커밋하지 않습니다.

```bash
cd src/android
export JAVA_HOME=/home/a/.local/opt/android-studio/jbr
export ANDROID_HOME=/home/a/Android/Sdk
./gradlew :engine:test :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease

# 기존 AVD와 앱 데이터를 보존하는 교체 설치. 서명이 다르면 삭제하지 말고 기존 서명을 확인합니다.
"$ANDROID_HOME/platform-tools/adb" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start --user 10 \
  -n com.sleepissue.awakedrive/.ui.DrivingPreviewActivity
```

위 명령의 user10은 이번 Pleos AVD의 운전자 사용자입니다. 다른 기기는 `adb shell am get-current-user`로 확인합니다. Wipe·앱 삭제·`pm clear`는 필요하지 않습니다. 마이크와 감시 알림 권한을 허용합니다. 실제 감시가 실행 중이면 알림의 **감시 중지**로 저장·종료한 뒤 데모를 엽니다.

| 버튼 순서 | 기대값 |
| --- | --- |
| 제품 홈 → **데모 → 시나리오 선택·재생** | 정차 상태에서만 열림. 제한 상태이거나 차량 서비스 확인 전이면 잠김 |
| **정상 / 딴짓 / 졸음 / 대화 / 보조 주행 / 출발 피로** 중 하나 | APK assets의 원본 JSON을 읽어 Player → Hub → 기존 판단 파이프라인으로 재생. 배지 **데모 재생 중**, 원본 건수·시나리오 시간 증가 |
| **일시정지 → 재개** | **데모 일시정지**로 구분한 뒤 재개. **중지 / 초기화** 시 배지 제거 |
| **졸음** 선택 직후 | 초기 연속·최근24시간 각각 7,800초에서 시작. 현재온도 23.5℃와 회전 거리 약80,000m를 원본에서 전달 |
| 질문 음성 시작 → 실제 대답 또는 판정 종료 | 실제 TTS 시작부터 1배속. 완료·실패·취소 뒤 원래 구간 배속으로 복귀. STT 미지원이면 대체 응답 버튼 |
| 재생 → **쉼터 P단 준비(모의) → 시동 끔(저장 후 앱 종료)** → 앱 아이콘으로 재실행 | 별도 데모 저장본 복원 + 데모 시계22분 이동. 휴식 인증1건·연속0초. 최근24시간·운행 ID·저장된 단계/기준선 참조 보존 |
| **Virtual Console 입력 우선 켜기** | 명시적으로 SDK 원천을 활성화하고 같은 Hub에서 유효한 REAL 입력 우선. 기본 재생은 SDK 입력을 끈 상태 |

제품 데모는 실제 내비 경유지·통풍 제어를 실행하지 않습니다. REAL 운행 DB와 분리한 데모 DB·설정을 사용합니다. 시동 끔은 앱 프로세스 종료와 디스크 복원 시연이며 실제 시동 OFF→ON/STR 검증이 아닙니다. 복원 결과 화면은 정지 상태이고 새 시나리오는 새 세션으로 시작합니다.

원본은 `data/scenarios/<id>.json`이며 APK의 `assets/<id>.json`에 직접 포함됩니다. 기존 `data/scenarios-demo` 사본에는 필드 손실이 있어 제품에서 사용하지 않습니다. `tripPreset`은 운전 구간 근거와 함께 데모 전용 상태를 구성합니다. [원본 비교·실행 결과](docs/devlog/2026-11-11-product-demo-release.md)를 확인하세요.

## 제출 APK·서명

2026-11-12 1번 범위 보완 후 **engine 351개 + app 427개 = 778개** 자동시험이 통과했습니다(실패·오류·건너뜀 0). debug·기기시험 APK·release 빌드와 release Lint vital도 성공했습니다. 전체 Lint는 기존 음성 코드 `PleosSpeechRecognizer.kt`의 마이크 권한 검사 오류 1건으로 실패합니다. 이번에는 해당 음성 코드를 수정하지 않았습니다.

현재 서명 전 산출물은 `apk/sleepissue_AwakeDrive_release-unsigned.apk`입니다. SHA-256은 `7a9fd6556711a90fd80b4eb29982f1e9063dbdafd66fcdcbfda676243ffc2437`이며 **제출용 서명 완료 APK가 아닙니다.** 최신 소스로 다시 빌드한 파일입니다. 원본 6종 JSON의 APK 포함·원본 일치와 debug 구성요소의 release 제외를 확인했습니다. 항목별 실제 기기 결과와 미해결 상태는 [이번 검증 기록](docs/devlog/2026-11-12-role1-listed-items.md)을 따릅니다.

`assembleRelease`의 기본 산출물은 `src/android/app/build/outputs/apk/release/app-release-unsigned.apk`입니다. 제출용 keystore/signingConfig가 없어 **서명 필요** 상태입니다. debug APK는 개발·기기시험용이며 제출용 서명이 아닙니다. 서명 없는 APK는 설치·기동 검증 대상이 아닙니다.

팀 서명 담당은 unsigned APK를 정렬하고 팀 keystore로 서명한 뒤 검증합니다. 비밀번호는 도구의 대화형 입력으로 넣고 저장소나 명령 기록에 적지 않습니다.

```bash
# ANDROID_BUILD_TOOLS는 설치된 build-tools 버전의 절대경로입니다.
"$ANDROID_BUILD_TOOLS/zipalign" -P 16 -f 4 \
  app-release-unsigned.apk AwakeDrive-aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks /안전한/경로/팀키.keystore \
  --out sleepissue_AwakeDrive_release.apk AwakeDrive-aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose sleepissue_AwakeDrive_release.apk
```

최종 제출 파일은 `apk/`에 두며 Git에는 추가하지 않습니다. debug 서명 앱과 팀 서명 앱의 인증서가 다르면 같은 패키지에 교체 설치할 수 없습니다. 기존 사용자 데이터 보존이 필요한 기기는 삭제하지 않고 별도 제출 검증 기기에서 설치·기동합니다.

## 알려진 한계와 담당 확인

- 조향 SDK의 전륜각 설명과 앱 운전대각 계약이 충돌하고, 착석 Boolean에는 UNKNOWN 손실이 있어 보수적 null을 유지합니다. 임의 조향비·Boolean 반전은 적용하지 않았습니다.
- 크루즈는 공개 Console Full autonomous 입력에서 실제 SDK ACTIVATED/Hub true를 확인했습니다(물리 차량·200ms 시한 검증 아님). NaviHelper 위치 이동/heading·현재 실내온도 변경·주행 중 TBT 감소는 여전히 입력 확인이 필요합니다. JSON 재생 성공을 실제 SDK 성공으로 세지 않습니다.
- 기존 조향 최대 약130ms/110ms 기준, 차속 전체지연 하한219ms/200ms 기준, 내비 목적지 터치→Hub 1초 초과는 미달로 유지합니다. PC/AVD·공급자 원인은 미확정입니다. TTL·timestamp·측정 기점을 바꾸지 않았습니다.
- 60배 실제 처리/최신성 미달 기록은 남아 있습니다. 원본 6종은 파일의 10배·1배 구간을 사용하며, 이번 구현만으로 60배 성능 통과를 주장하지 않습니다.
- 실제 마이크·TTS/STT는 ON_DEVICE를 사용합니다. 제품 감시 서비스의 백그라운드 마이크 FGS와 녹음 음성 대체 리소스는 별도 보완 대상입니다. 대화 JSON의 동승자 착석과 실제 말소리 감지는 다릅니다.
- 출발 피로 15분 TC의 평소 기준선3회 사전조건과 엔진 내부 학습 창의 재실행 직렬화는 아직 별도 확인/연결이 필요합니다. 이번 재실행은 정밀 운행·개입 checkpoint·휴식 상태·기준선 참조를 보존합니다.

계정 전환 시의 정확한 진행 상태·검증된 APK와 재개 명령은 [CURRENT_HANDOFF](docs/setup/CURRENT_HANDOFF.md)를 먼저 읽습니다. 현재 unsigned release는 2026-11-12 소스의 빌드입니다. 팀 키 서명과 서명 APK의 별도 설치·실행 확인은 남아 있습니다.
