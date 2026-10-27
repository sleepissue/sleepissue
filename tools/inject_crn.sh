#!/usr/bin/env bash
# CRN을 에뮬레이터 VHAL에 주입한다. 에뮬레이터를 Wipe Data 했으면 다시 실행해야 한다.
# CLAUDE.md 6장의 명령을 그대로 옮긴 것. CRN 값은 .env(저장소 루트, git에 올리지 않음)에서 읽는다.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "오류: 저장소 루트에 .env 파일이 없습니다. .env.example을 복사해 CRN 값을 채우세요." >&2
  exit 1
fi

set -a
source .env
set +a

if [ -z "${CRN:-}" ]; then
  echo "오류: .env에 CRN이 비어 있습니다." >&2
  exit 1
fi

adb root
adb shell su 0 "echo 'propId: 554696961 areaId: 0 values: ${CRN}' > /data/vendor/vsomeip/vhal_fifo"
adb reboot

echo "CRN 주입 완료, 에뮬레이터를 재부팅합니다."
