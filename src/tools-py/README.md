# src/tools-py

데모 시나리오 신호 합성·검증(Python), 졸음쉼터 CSV 전처리. (CLAUDE.md 10장)

- 입력: `data/rest_areas/`의 원본 CSV, 6종 시나리오 설계값
- 출력: `data/scenarios/`의 10Hz JSON 6종(정상·딴짓·졸음·대화·보조주행·출발피로), `data/rest_areas/`의 전처리본

5번(화면·시연) 담당.

## 데모 trace 단일 세션 분석

```bash
python3 src/tools-py/analyze_demo_trace.py /tmp/awakedrive-user-demo-trace-20261106-repeat.jsonl \
  --session 4 --output /tmp/demo-session4-analysis.json --extract /tmp/demo-session4.jsonl
```

`--session`은 필수다. 파일 전역 누계는 다른 재생을 섞으므로 쓰지 않고, 해당 세션 요약과 상세만 추출한다.
Hub의 `skippedDeadlines`는 발행 시한 누락이다. 원본 전달 누락이나 상세 기록 간격과 구분한다.
기존 trace는 상세를 주기적으로만 보관하므로 원본별 순서·누락·중복은 그 파일만으로 증명할 수 없다.

## 짧은 배속·장기 재생의 시간축 감사

```bash
python3 src/tools-py/analyze_demo_timeaxis.py \
  --report-dir src/android/app/build/reports/demo-timeaxis-20261106 \
  --output /tmp/demo-timeaxis-analysis.json
```

독립 실제 시작→처리완료 경과, Clock 목표, Flow 인계완료 시각, Hub 발행시각을 구분한다. 고정된짧은6run·장기4run만 감사하고 별도스모크는 합산하지 않는다. 원본3채널 순서/누락/중복/hash와 발행누락·최종tail을 따로 확인한다. `DemoClock.realElapsedMs`는 종료 목표에서고정될수있어달성배속의분모로쓰지않는다. [시간축검토](../../docs/devlog/2026-11-06-demo-timeaxis.md)를 따른다.
