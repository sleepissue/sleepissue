#!/bin/sh
# 사용법: CRN은 .env 의 CRN=... 에서 읽는다 (커밋 금지)
set -e
. "$(dirname "$0")/../.env"
adb root
sleep 2
adb shell su 0 "echo 'propId: 554696961 areaId: 0 values: ${CRN}' > /data/vendor/vsomeip/vhal_fifo"
adb reboot
