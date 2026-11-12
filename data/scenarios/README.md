# 제품 데모 시나리오

2026-11-11: 제품 **데모 → 시나리오 선택·재생**에서 정상·딴짓·졸음·대화·보조 주행·출발 피로 원본6종을 직접 읽는다. 이 폴더를 Gradle assets 경로로 묶어 APK `assets/<id>.json`에 배포한다. 원본의 `formatVersion/scenarioId/startAt/tMs`를 `DemoJsonLoader`가 엄격 검증하며, 기존 정규 형식 `schemaVersion/id/startEpochMs/t`도 계속 지원한다.

`tripPreset`은 DEMO에만 적용하고 운전 구간 근거와 함께 시작한다. 회전 거리·현재 실내온도·시작 날짜·배속은 생략하지 않는다. 기존 `data/scenarios-demo` 사본은 preset과 일부 필드가 누락되어 제품에서 사용하지 않는다. 원본과 사본은 보존했다. [차이·실행 결과](../../docs/devlog/2026-11-11-product-demo-release.md), [버튼 순서·한계](../../README.md).

`demo.schema.json`은 정규 형식의 구조 설명이며, 팀 원본의 별도 스키마인 것처럼 쓰지 않는다. 최종 검사는 로더 한 곳에서 한다. `demo_minimal.json`은 짧은 로더 예제다. JSON 파일의 착석은 실제 말소리 입력을 대신하지 않는다. 출발 피로 TC의 평소 기준선3회 사전조건은 별도 준비가 필요하다.

[JSON 작성 가이드](../../docs/setup/ROLE1_DEMO_JSON.md)를 따른다. `demo_minimal.json`은 로더 확인용 0.3초 예제이며, 정상·딴짓·졸음·대화·보조 주행·출발 피로 6종의 완성 시나리오가 아니다.

`demo.schema.json`은 구조 검사에 쓴다. 실제 로더는 구조뿐 아니라 10 Hz 간격, 시간 역행, 좌표 쌍과 배속 구간도 검사한다. 원본 파일은 UTF-8 JSON으로 저장한다. 주석·중복 키·NaN/Infinity는 허용하지 않는다.

파일 이름과 `id`를 같게 쓰며, 앱 로더에는 파일 경로 대신 `id`를 전달한다. 작성한 파일을 debug 검증 화면에서 직접 불러오거나 JSON 입력에 붙여 넣어 확인할 수 있다.
