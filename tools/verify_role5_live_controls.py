"""직접 발화 없이 제품 설정·동승자 버튼을 검증하고 권한·차량 조작 값을 복구한다."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading
from urllib.parse import urlencode
from urllib.request import build_opener, ProxyHandler, Request

PACKAGE = 'com.sleepissue.awakedrive'
CLIENT = 'awakedrive-live-controls'
HTTP = build_opener(ProxyHandler({}))
BASE = 'http://127.0.0.1:8088/'


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=40).strip()


def request(path, method='GET'):
    with HTTP.open(Request(BASE + path, method=method), timeout=5) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('/tmp/awakedrive-live-controls'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if 'isForeground=true' in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE):
        raise RuntimeError('기존 실제 감시를 중지한 뒤 실행하세요. 실행 중인 감시는 변경하지 않습니다.')
    user = adb('shell', 'am', 'get-current-user')
    dump = adb('shell', 'dumpsys', 'package', PACKAGE)
    section = dump.split(f'    User {user}:', 1)[1].split('\n    User ', 1)[0]
    permissions = {}
    for name in ('android.permission.RECORD_AUDIO', 'android.permission.POST_NOTIFICATIONS', 'android.car.permission.CAR_SPEED'):
        match = re.search(re.escape(name) + r': granted=(true|false)', section)
        # 미허용·플래그 없음이면 dumpsys가 해당 항목을 생략할 수 있다.
        install = re.search(re.escape(name) + r': granted=(true|false)', dump.split('    User ', 1)[0])
        permissions[name] = (match or install)[1] == 'true' if (match or install) else False
    request('browser/session?' + urlencode({'client': CLIENT}), 'POST')
    original = request('vehicle/state')
    gear = (original['gear'].get('actual') or {}).get('value')
    if gear not in original['gear']['supported_gears'] or not original['controls']['dds_ready']['all']:
        raise RuntimeError('공식 콘솔의 현재 기어·조작 연결을 확인할 수 없습니다.')
    saved = {target: original['controls'][source] for target, source in
             [('speed', 'speed_kph'), ('accelerator', 'accelerator_pct'), ('brake', 'brake_pct'), ('steering', 'steering_deg')]}
    (args.output / 'before.json').write_text(json.dumps({'permissions': permissions, 'gear': gear, 'controls': saved}, indent=2))
    stop = threading.Event()

    def heartbeat():
        while not stop.wait(.8):
            request('browser/session?' + urlencode({'client': CLIENT}), 'POST')

    thread = threading.Thread(target=heartbeat, daemon=True)
    thread.start()
    try:
        request('vehicle/controls?' + urlencode({'speed': 0, 'accelerator': 0, 'brake': 0}), 'PUT')
        request('vehicle/gear?gear=Park', 'PUT')
        for name in permissions:
            adb('shell', 'pm', 'revoke' if name.endswith('RECORD_AUDIO') else 'grant', '--user', user, PACKAGE, name)
        with (args.output / 'instrumentation.log').open('w') as log:
            result = subprocess.run(['adb', 'shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'role5LiveControls', 'true', '-e', 'class',
                'com.sleepissue.awakedrive.ui.LiveControlsDeviceTest', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'],
                stdout=log, stderr=subprocess.STDOUT, timeout=180)
        report = adb('exec-out', 'run-as', PACKAGE, '--user', user, 'cat', 'cache/live-controls-device.json')
        (args.output / 'result.json').write_text(report)
        log = (args.output / 'instrumentation.log').read_text()
        if result.returncode or 'OK (1 test)' not in log or 'FAILURES' in log:
            raise RuntimeError('기기 시험 실패: ' + str(args.output / 'instrumentation.log'))
        print(report)
    finally:
        # 시험의 finally에서 감시·설정을 먼저 복구한다. 권한을 되돌리면 프로세스가 종료될 수 있다.
        try:
            request('vehicle/controls?' + urlencode({'speed': 0, 'accelerator': 0, 'brake': 0}), 'PUT')
            request('vehicle/gear?' + urlencode({'gear': gear}), 'PUT')
            request('vehicle/controls?' + urlencode(saved), 'PUT')
        finally:
            for name, granted in permissions.items():
                adb('shell', 'pm', 'grant' if granted else 'revoke', '--user', user, PACKAGE, name)
            stop.set()
            thread.join(timeout=5)
            request('browser/session?' + urlencode({'client': CLIENT, 'closing': 1}), 'POST')
            (args.output / 'restored.txt').write_text('권한과 가상 차량 조작 값을 복구했습니다.\n')
            print('권한과 가상 차량 조작 값을 복구했습니다.')


if __name__ == '__main__':
    main()
