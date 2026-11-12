# 2026-11-12 1번 지정 항목 검증 근거

최종 기준은 tests.json(778개), final-build.log, product-final.log(기기3개), final-product-demo-voice-20261111.json, navi-final.log/json/summary, signals-final.log/json.gz/summary, apk-final.json, scope.json, preservation.json이다. product-device.log는 중간 SDK 버전의 앞선3건이며 최종3건과 합산하지 않는다. navi-before는 수정 전이고, 중간 재조회 버전의 결과는 개인 백업에만 남겼다.

원본 DB/설정 tar·keystore·SDK 소스/바이너리는 포함하지 않았다. signals-final.json.gz는 이번 가상 차량 SDK 원시 진단이며 사용자 Room DB가 아니다. UI XML의 프로세스 종료/복원은 별도 수동 시연이고 실제 STR 시험은 아니다. SDK/내비 runner의 OK는 계측 정상 종료를 뜻한다. 110ms·1초 시한 미달 상태는 바꾸지 않는다.
