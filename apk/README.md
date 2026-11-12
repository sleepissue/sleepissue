# apk/

**2026-11-12 현재:** 사용자 요청으로 APK 생성·서명·설치를 중단했다. 아래 unsigned 파일은 이전 작업 산출물이며 이후 수정한 현재 소스와 일치한다고 보증하지 않는다. [현재 상태](../docs/setup/ROLE1_DEMO_PRODUCT_STATUS.md)를 우선 확인한다.

제출용 APK를 여기에 둡니다. 파일명 규칙(CLAUDE.md 10장): `팀명_AwakeDrive_release.apk`

`*.apk`는 `.gitignore`로 제외되어 있어 이 폴더 자체는 git에 추적되지 않는 내용(APK 파일)을 위한 자리입니다. 제출 직전에 로컬에만 올려 두고, 접수는 공모전 안내에 따르세요.

2026-11-12 현재 로컬 산출물은 `sleepissue_AwakeDrive_release-unsigned.apk`입니다. 팀 서명이 없는 준비 파일이며 최종 제출 파일과 구분합니다. SHA-256은 `7a9fd6556711a90fd80b4eb29982f1e9063dbdafd66fcdcbfda676243ffc2437`입니다. [서명·검증 방법](../README.md#제출-apk서명)을 따라 팀 키로 서명하고 별도 기기에서 설치·실행을 확인해야 합니다. 기존 데이터가 있는 debug 앱을 삭제해 인증서 충돌을 해결하지 않습니다.
