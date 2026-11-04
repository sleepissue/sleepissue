# src/tools-py

데모 시나리오 신호 합성·검증(Python), 졸음쉼터 CSV 전처리. (CLAUDE.md 10장)

- 입력: `data/rest_areas/`의 원본 CSV, 6종 시나리오 설계값
- 출력: `data/scenarios/`의 10Hz JSON 6종(정상·딴짓·졸음·대화·보조주행·출발피로), `data/rest_areas/`의 전처리본

5번(화면·시연) 담당.

## 쉼터 전처리 (4번, REQ_RST_001)

```bash
python3 src/tools-py/preprocess_rest_areas.py          # 저장소 루트에서. 검사 통과 시 저장
python3 src/tools-py/preprocess_rest_areas.py --check  # 검사만
```

- 입력: `data/rest_areas/전국졸음쉼터표준데이터.csv`, `전국휴게소정보표준데이터.csv` (공공데이터포털, CP949)
- 출력: `data/rest_areas/processed/rest_areas.csv` (UTF-8) — 앱 assets로 그대로 들어간다
- 원본을 새로 받으면 다시 돌리고, `./gradlew :app:testDebugUnitTest`(RestAreaRepositoryTest)로 확인한다
- 열 설명·방향 계산 방법은 스크립트 맨 위 주석 참고
