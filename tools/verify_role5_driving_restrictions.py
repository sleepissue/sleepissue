"""제품 화면 기기 시험. 기존 앱·시험 APK 설치 후 실행하며 시험 전 권한·조작 값을 복구한다."""
import json, subprocess, time, threading
from pathlib import Path
from urllib.request import build_opener, ProxyHandler, Request
from urllib.parse import urlencode

package = 'com.sleepissue.awakedrive'
base = 'http://127.0.0.1:8088/'
client = 'awakedrive-ux-test'
http = build_opener(ProxyHandler({}))
stop = threading.Event()

def request(path, method='GET'):
    with http.open(Request(base + path, method=method), timeout=5) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()

def heartbeat():
    while not stop.is_set():
        try:
            request('browser/session?' + urlencode({'client': client}), 'POST')
        except Exception:
            pass
        stop.wait(0.8)

thread = threading.Thread(target=heartbeat, daemon=True)
thread.start()
user = adb('shell', 'am', 'get-current-user')
original = request('vehicle/state')
gear = original['gear'].get('actual', {}).get('value')
if gear not in original['gear']['supported_gears']:
    raise RuntimeError('원래 기어 값을 확인할 수 없습니다.')
controls = original['controls']
saved = {'speed': controls['speed_kph'], 'accelerator': controls['accelerator_pct'],
         'brake': controls['brake_pct'], 'steering': controls['steering_deg']}
Path('/tmp/sleepissue-ux-console-before.json').write_text(json.dumps({'gear': gear, 'controls': saved}, indent=2))
process = None

def set_controls(**values):
    request('vehicle/controls?' + urlencode(values), 'PUT')

def set_gear(value):
    request('vehicle/gear?' + urlencode({'gear': value}), 'PUT')

def wait_file(name):
    end = time.monotonic() + 40
    while time.monotonic() < end:
        result = subprocess.run(['adb', 'exec-out', 'run-as', package, '--user', user, 'cat', 'cache/' + name], capture_output=True)
        expected = {'ux-test-ready': '정차 설정 화면 준비', 'ux-test-locked': '실제 주행 제한과 화면 잠금 확인'}[name]
        if result.returncode == 0 and result.stdout.decode().strip() == expected:
            return
        if process.poll() is not None:
            raise RuntimeError('시험이 준비 신호 전에 종료됐습니다: ' + name)
        time.sleep(0.15)
    raise RuntimeError('시험 준비 신호 대기 시간 초과: ' + name)

try:
    end = time.monotonic() + 10
    while not request('vehicle/state')['controls']['dds_ready'].get('all'):
        if time.monotonic() >= end:
            raise RuntimeError('공식 콘솔의 조작 입력 준비 실패')
        time.sleep(0.2)
    set_controls(speed=0, accelerator=0, brake=0)
    set_gear('Park')
    adb('shell', 'run-as', package, '--user', user, 'rm', '-f', 'cache/ux-test-ready', 'cache/ux-test-locked')
    with open('/tmp/sleepissue-driving-ui-device.log', 'w') as log:
        process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'role5DrivingUi', 'true', '-e', 'class',
            'com.sleepissue.awakedrive.ui.DrivingRestrictionDeviceTest', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=log, stderr=subprocess.STDOUT)
        wait_file('ux-test-ready')
        set_gear('Drive')
        set_controls(speed=60, accelerator=0, brake=0)
        wait_file('ux-test-locked')
        Path('/tmp/sleepissue-driving-native-restricted.txt').write_text(adb('shell', 'dumpsys', 'car_service', '--services', 'CarUxRestrictionsManagerService'))
        set_controls(speed=0, accelerator=0, brake=0)
        set_gear('Park')
        process.wait(timeout=45)
    Path('/tmp/sleepissue-driving-ui-result.json').write_text(adb('exec-out', 'run-as', package, '--user', user, 'cat', 'cache/driving-restriction-ui.json'))
finally:
    try:
        set_controls(speed=0, accelerator=0, brake=0)
        set_gear(gear)
        set_controls(**saved)
    finally:
        stop.set()
        thread.join(timeout=5)
        request('browser/session?' + urlencode({'client': client, 'closing': 1}), 'POST')
        if process is not None and process.poll() is None:
            process.wait(timeout=45)
        print('원래 가상 차량 조작 값으로 복구했습니다.')
