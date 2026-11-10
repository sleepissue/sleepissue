# SIG_016 — 같은 이미지·APK의 다른 PC 재현 절차

**현재 상태: 다른 PC 실행/비교는 미실행.** 이 문서는 [같은 PC의 현재/데몬 정리 비교](../devlog/2026-11-10-sig016-host-load.md)를 다른 호스트에서 반복하기 위한 절차다. 호스트 차이만으로 SDK/앱 원인을 확정하는 절차는 아니다.

## 고정할 조건

| 항목 | 이번 기준 |
| --- | --- |
| 이미지 | Pleos Connect2.1.0/API34/x86_64, package revision1, build fingerprint `Pleos/crp_avd/crp_avd_x86_64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys` |
| AVD | 실제4 vCPU/4096MB,2560×1440/160dpi. 이미지 템플릿 기본8192MB가 아닌 실제 설정을 대조 |
| 가속 | 이번은 KVM·host Intel GPU/OpenGL ES3.0, Vulkan 미사용. 다른 PC의 실제 VM/그래픽 가속 유형·driver·renderer도 기록. 지원 여부만으로 실사용 판정 금지 |
| 공급자 | 기본 내비3.3.2-rc5-hotfix.3/NaviService3.0.4, 정상 SDK 연결·기존과 같은 권한, 한국어 지도 UI |
| 제품 APK SHA256 | `d996f17a4bf2557da0b40a1a47f10ec919ef98185e2da1518edde70f7bc2ff0d` |
| 시험 APK SHA256 | `30e4fa1ac1f74ddd9b885f74627405261e1dc50b29986c9c9820b5f85dd8a722` |
| 입력 | 실제SDK·P/0, 기존 목적지/미리보기 없음→공식sample42dot 요청→보이는 안내시작 정상터치→60초/50m 수신→자기경로 취소/null |
| 계측 | 기존 `role1Sig016Boundaries=true`,3회/runner. Clock·1000ms 조회·TTL·Hub 주기·코드 변경 없음. 호스트2초/guest4초 통계를 두 조건에 똑같이 사용 |

큰 이미지 파일(system.img/vendor.img/ramdisk.img)·동일 배포 아카이브의 SHA256은 **측정창 밖에서** 확인한다. 이번 PC에서는 큰 system.img 내용 해시를 산출하지 않았고 다른 PC와 바이너리 동일성을 대조하지 않았다. 버전 문자열 일치와 파일 내용 일치를 구분한다.

기존 사용자 AVD의 RAM·코어·GPU·권한·Console 상태를 임의 변경해 맞추지 않는다. 이미 준비된 시험 전용 AVD가 조건과 다르면 차이를 기록하고 동등 비교를 보류한다. 사용자 DB·진단·CRN·키를 다른 PC로 복사하지 않는다. SDK 초기화는 각 기기의 공식 준비 상태를 사용한다. 시험 APK/SDK 바이너리를 Git/공개 자료로 배포하지 않는다.

## 준비·현재 환경 실행

1. APK2개를 허용된 별도 경로로 전달하고 해시를 대조한다. 필요한 경우 `adb install -r -t`로 데이터 유지 설치한다. 빌드해서 바뀐 APK를 같은 APK라고 부르지 않는다.
2. 사용자 경로/미리보기·감시/SDK 소유자가 있으면 그대로 시험하지 않는다. 안전한 시험 전용 조건을 마련하고 정상 UI로 종료할 수 있는 조건에서만 진행한다. 앱 삭제·force-stop·데이터 초기화·Wipe는 사용하지 않는다.
3. CPU/RAM/swap·AVD 실제CPU/메모리·renderer·가속 실사용·공급자 버전·권한·원래 입력을 읽기 전용 기록한다. DB/prefs/cache·기존 진단의 시험 전후 보존 대조도 준비한다.
4. 빌드·IDE indexing·Perfetto/기타 tracing의 동시 실행 여부를 기록한다. 필수 Console/DDS/SDK 서비스·화면 조건은 유지한다. 측정 중에 빌드/추가 trace를 시작하지 않는다.
5. 기존 계측으로 현재 조건 runner2회×3을 실행한다. 매번 새runId를 쓰고 원본을 새 파일로 보관한다. 아래는1실행의 명령이며 다른 PC의adb 경로/serial/user를 명시적으로 확인한다.

```bash
adb shell am start -W --user 10 -n ai.umos.maps.android.navigation.app/.MainActivity
probe_run_id=sig016_peer_current1_20261110
adb shell am instrument --user 10 -w \
  -e role1Sig016 true -e role1Sig016Boundaries true -e runId "$probe_run_id" \
  -e class com.sleepissue.awakedrive.data.Role1NaviDeadlineProbeTest \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.sleepissue.awakedrive --user 10 \
  cat "cache/role1-sig016-$probe_run_id.json"
```

이번 호스트 자원 수집 [measure.py](../devlog/evidence/2026-11-10-sig016-host/measure.py)는 Linux `/proc` 기반이고 도구·IDE로그 경로는 이번 PC 값이다. 다른 PC에서는 **제품 코드/계측APK는 그대로**, 외부 통계 수집의 경로만 해당 호스트에 맞춰야 한다. 다른 OS의 동등 자원 수집/가속 확인은 이번에 실행하지 않았다. 이 스크립트가 모든 OS에서 그대로 동작한다고 가정하지 않는다.

## 정리 환경 실행과 복구

1. 현재 측정이 끝난 뒤에만 유휴 빌드 데몬·불필요한 사용자 작업을 정상 종료한다. 작업 중인 IDE·미저장 문서·필수 서비스는 임의 종료하지 않는다. 불필요한 부하가 없다면 억지 부하를 만들지 않는다.
2. CPU/RAM/swap을 다시 읽고 약20초 안정화한다. **실제로 CPU가 낮아졌는지 확인**한다. 메모리만 증가했다면 ‘메모리 정리 비교’, CPU/AVD 부하가 여전하면 ‘CPU 저부하 대조 미확보’라고 쓴다. 본 PC 비교가 이 경우다.
3. 같은 APK·AVD·화면·입력·계측으로 runner2회×3을 추가 실행한다. 전후 해시/boot_id/설정이 같고 모든 시도를 공개해야 한다. warm-up/순서가 다르므로 단순 차이를 정리 효과로 단정하지 않는다.
4. `attempts[].activeReached`·원시60초/50m·route mode·Hub·취소null을 각각 확인한다. 값/입력 조건이 다르면 실패/조건 불일치로 남기고, 시간 표본만 골라 통과시키지 않는다. JUnit OK는1초 Pass가 아니다.
5. 종료 후 자기 경로만 취소됐는지, SDK leaseReleased=true/ownerAfterRelease=null/hubAfterClose=null인지 확인한다. 정상 서비스 종료·사용자 DB/진단/설정/권한/Console 보존을 대조한다. 다른 담당 기능을 켜거나 고치지 않는다.

## 비교할 항목·판정 제한

- 원래 터치 before/after bracket·±2ms를 유지한다. ‘목적지 있음’과 터치의 동치가 원문에서 미정이라는 사실도 유지한다. SDK 수신을 전체 시한의 새 시작점으로 선택하지 않는다.
- 입력→다음조회 위상 대기, timer due→복귀, 선행 SDK 호출, destination 시작→반환, 첫 반환→첫 콜백 공백, 콜백→어댑터/Hub,1Hz 소비자를 따로 비교한다.
- 공개 callback에 요청 ID가 없으므로 중간 추가조회가 있는 공백은 RTT/provider 단독 처리시간이 아니다.35ms 같은 짧은 표본이나 실패도 모두 공개한다.
- 호스트/guest CPU·swap I/O·메모리·IDE/빌드/trace 겹침을 함께 기록한다. 저빈도 자원 표본은 수백ms 지연의 직접 인과를 증명하지 못한다.
- 비교는 처음 요청 회차와 뒤의 회차도 구별한다. 같은 PC의 정리 전후 순서·PID 초기화·cache/JIT/열 상태가 섞일 수 있다. 시간 차이만으로 CPU/SDK/코드 원인을 확정하지 않는다.
- 실제 다른 PC 원본을 확보하기 전에는 **절차 작성 완료 / 다른 PC 비교 미실행**으로 유지한다.
