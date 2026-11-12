# 2026-11-11 제품 데모 6종 기기 검증

Pleos Connect v2.1.0, API 34, 소프트웨어 그래픽 콜드 부팅, 기존 앱 데이터 보존. 제품 `ProductDemoSession`을 Android 기기 프로세스에서 각각 요청 60배로 실행했다. Activity 터치 시험과 구별하며 실제 SDK·음성·내비를 실행하지 않는다.

기기 시험 6개 모두 COMPLETED·종료 시각 2,710,000ms를 확인했다. **이 6개 통과는 아래 기능 결함이 해결됐다는 뜻이 아니다.** 엔진346개·앱440개 단위 시험 및 debug/AndroidTest APK 빌드 성공. 실행 원본은 [evidence](evidence/2026-11-11-role5-product/summary.json)에 보존했다.

| 시나리오 | 종료 | 실제 경과(초) | 최대 단계 | 휴식 인증 | 스탬프 | 앞쪽 쉼터 |
| --- | --- | --- | --- | --- | --- | --- |
| normal | COMPLETED | 50.046 | 1 | True | 0 | NotFound |
| distraction | COMPLETED | 49.391 | 1 | True | 0 | NotFound |
| drowsy | COMPLETED | 53.417 | 3 | True | 1 | NotFound |
| talk | COMPLETED | 49.985 | 2 | True | 1 | NotFound |
| assist | COMPLETED | 78.445 | 2 | True | 1 | NotFound |
| departure | COMPLETED | 50.879 | 2 | True | 1 | NotFound |

## 미완료·불일치

- normal: 끝까지 0단계여야 하지만 45:10에 SAFETY_EVENT로 1단계가 관찰됐다. 모델 직결 시험과 제품 Hub 경유 시험의 차이이며 원인 수정·회귀 시험이 필요하다.
- 6종 모두 앞쪽 쉼터 NotFound. 휴식 반경 인증 성공과 진행 방향에 맞는 경유지 후보 검색 성공은 별개다. 현재 데모 내비의 heading 입력·계산 연결이 빠져 있다.
- talk는 확인 단계 발생을 보지만 실제 대화 감지·동승자 음성 확인은 수행하지 않는다.
- 제품 데모의 실제 음성 질문·응답 시간·내비 추가·취소·회복 확인은 미연결이다. 파워냅 버튼·알람 소리는 이 자동 시험에서 실행하지 않았다.
- 요청 60배 대비 실제 경과를 위 표에 기록했다. 실제 60배 정시 재생·원본 전수 전달 무결성의 합격 판정은 이 시험 범위가 아니다.
- 2번의 `12-demo-expected.md`와 `runner.epochOffsetMs`는 원격에도 아직 없어 기대 시점의 최종 대조가 남는다.

## 관찰한 단계 전환

- normal: 45:10 1단계 SAFETY_EVENT · 위험도 0.015223684
- distraction: 45:10 1단계 SAFETY_EVENT · 위험도 3.0219862
- drowsy: 18:40 1단계 SCORE · 위험도 43.27389; 20:10 2단계 SAFETY_EVENT · 위험도 48.865627; 26:40 3단계 SCORE · 위험도 80.59295
- talk: 16:20 1단계 SAFETY_EVENT · 위험도 0.003581193; 16:50 2단계 SAFETY_EVENT · 위험도 4.864444
- assist: 15:10 1단계 SAFETY_EVENT · 위험도 0.0; 15:40 2단계 SAFETY_EVENT · 위험도 0.0
- departure: 04:40 1단계 SCORE · 위험도 40.57293; 15:20 2단계 SCORE · 위험도 55.194706
