# 1번 감사 증거(11/11 시작,11/12 정리)

**현재 판정과 구분:** 아래 파일은 당시 실패·실험 원본이다. 중간 첫 확인 수정은 사용자 범위 정정으로 원복됐고, 이후 간트1번 task32 범위의 최종 예약 회귀는 `signal/FirstCheckScheduleTest.kt`·`service/AwakeDrivePipelineTest.kt`와 [최신 실행 기록](../../2026-11-12-product-demo-usable.md)을 따른다. 아래 과거 `SensitiveFirstCheckPipelineTest.kt` 참조는 현재 상시 시험 파일이 아니다.

실패 원본을 성공으로 덮어쓰지 않았다. `Role1CompletionAuditTest.kt`와 `Role1ProductAuditDeviceTest.kt`는 감사 재현 소스 보관본이며 현재 앱 test source가 아니다. REAL 단계/위치 저장 및 제품 departure 실패는 미수정이다. 첫 예약 수정 후 상시 회귀는 `service/SensitiveFirstCheckPipelineTest.kt`를 본다.

`product-voice.json`의 completed는 fixture 종료를 뜻한다. 실제 결과 FAILED, 마이크 미허용 상태의 배속 잠금 해제 검증이다. `scenario-schema-apk.json` raw 검사는 canonical 전용 schema와 입력 형식을 혼용한 결과이며 loader의 제품 로드 실패가 아니다.

전체 판정은 [감사 보고](../../2026-11-12-role1-audit.md)를 본다. 사용자 DB·키·환경 원문은 포함하지 않았다.
