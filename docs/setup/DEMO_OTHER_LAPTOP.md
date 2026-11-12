# 다른 노트북에서 전체 데모 실행

실행 브랜치: `feat/ui-demo-handoff-1112`. Android Studio에서는 저장소의 **src/android/**를 연다. 루트의 별도 android/ 템플릿은 사용하지 않는다.

```bash
git fetch origin
git switch --track origin/feat/ui-demo-handoff-1112
cd src/android
./gradlew :engine:test :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am start -n com.sleepissue.awakedrive/.ui.DrivingPreviewActivity
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`를 사용한다. Android SDK 경로는 각 PC의 local.properties로 지정하고 커밋하지 않는다. 프로젝트 compile SDK는 37이며 Gradle의 JDK 지정 파일을 따른다. Pleos SDK는 Gradle 저장소에서 내려받는다.

## 에뮬레이터와 소리

Pleos Connect v2.1.0을 Cold Boot한다. 음성 시연에서는 `-no-audio` 옵션을 사용하지 않는다. PC 마이크 입력이 필요하면 `adb emu avd hostmicon`을 실행한다. Virtual Console은 `adb forward tcp:8088 tcp:8088`로 연결한다. CRN은 해당 노트북의 .env에만 넣고 저장소의 tools/inject_crn.sh 안내를 따른다. SDK 바이너리·CRN·개인 SDK 경로는 PR에 포함하지 않는다.

이전 PC에서는 차량 소리 설정의 **벨소리/알람 그룹 35/45**에서 파워냅 알람이 들렸다. 레거시 알람 음량이 최대여도 별도 차량 그룹 음량을 확인한다. 마이크 추가 부스트 +30dB에서 조용한 조건 오탐이 있었고 추가 부스트 0dB에서 조용함 false·발화 true를 확인했다. 다른 노트북은 직접 청취·발화로 점검한다.

## 시연 순서

제품 화면의 하단 데모에서 정상 → 딴짓 → 졸음 → 대화 → 보조 주행 → 출발 피로를 고르고 60배 → 불러오기 → 재생한다. 파워냅 기상 버튼 확인은 정상 10배속으로 진행하면 조작 여유가 있다. 실제 감시를 먼저 켠 상태에서는 데모를 시작하지 않는다. 마이크 동의가 꺼졌거나 STT가 불가능하면 운전자 응답 버튼을 사용한다. 음성 질문은 사용자 지시대로 항상 운전자에게 한다. 동승자 버튼을 쓰려면 설정에서 동승자 확인을 켠다.

실제 내비 경유지 성공 시연에는 내비 안내 경로를 먼저 준비한다. 경유지 성공은 앱 문구와 실제 내비 반영을 함께 확인한다. 이 브랜치는 내비 화면을 열 때 데모를 일시정지하고 복귀하면 이어간다.

수동 음성 검증 화면:

```bash
adb shell am start -S -n com.sleepissue.awakedrive/.voice.VoiceValidationActivity --ez manual_validation true
```

‘전체 검증 다시 실행’은 안내 → 신호음 → 직접 ‘네’ 응답 → 10초 말소리 검사 순서다. ‘파워냅 알람 10초 청취’로 소리를 점검한다. 원본 음성은 저장하지 않는다.

## 확인된 범위와 남은 사항

동승자 버튼, 내비 경유지 목록 반영·화면 전환·복귀·휴식 인증·회복, 파워냅 알람 뒤 기상 버튼을 확인했다. 직접 ‘네’ 발화와 안내·신호음·알람 청취, 조용함/발화 말소리 감지도 부품 화면에서 확인했다. 상세 결과는 [리허설 기록](../devlog/2026-11-12-product-ui-rehearsal.md)을 따른다.

실제 대화 감지를 제품 흐름에 연결하는 미완성 변경은 실행 브랜치에서 제외했다. 제품은 아직 speechDetected=false 입력을 사용한다. 기상 직후 회복까지의 전체 흐름과 촬영 문구 정리는 남아 있으므로 전체 합격으로 표시하지 않는다. 시나리오 6종·쉼터 데이터는 추적된 data/에서 APK에 포함되므로 로컬 임시 data/scenarios-validation이나 debug/assets/drowsy.json은 필요 없다.
