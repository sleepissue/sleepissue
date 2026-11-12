"""제품 화면 기기 시험. 기존 앱·시험 APK 설치 후 실행하며 시험 전 권한·조작 값을 복구한다."""
import subprocess, re, json
from pathlib import Path

package = 'com.sleepissue.awakedrive'

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()

user = adb('shell', 'am', 'get-current-user')
dump = adb('shell', 'dumpsys', 'package', package)
section = dump.split(f'User {user}: ceDataInode', 1)[1].split('\n    User ', 1)[0].split('\nQueries:', 1)[0]
installed = re.split(r'\n    User \d+:', dump, maxsplit=1)[0]
permissions = ['android.permission.RECORD_AUDIO', 'android.car.permission.CAR_SPEED', 'android.permission.POST_NOTIFICATIONS']
before = {}
for permission in permissions:
    match = re.search(re.escape(permission) + r': granted=(true|false)(?:, flags=\[([^]]*)\])?', section)
    if not match:
        match = re.search(re.escape(permission) + r': granted=(true|false)(?:, flags=\[([^]]*)\])?', installed)
    # 사용자별 항목과 설치 시 허용 항목 모두 없으면 기본 거절 상태다.
    before[permission] = {'granted': match is not None and match[1] == 'true', 'flags': (match[2] or '') if match else ''}
Path('/tmp/sleepissue-permissions-before.json').write_text(json.dumps(before, indent=2))
try:
    adb('shell', 'pm', 'revoke', '--user', user, package, 'android.permission.RECORD_AUDIO')
    adb('shell', 'pm', 'clear-permission-flags', '--user', user, package, 'android.permission.RECORD_AUDIO', 'user-set', 'user-fixed')
    with open('/tmp/sleepissue-permission-ui-device.log', 'w') as log:
        subprocess.run(['adb', 'shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'role5PermissionUi', 'true', '-e', 'class',
            'com.sleepissue.awakedrive.ui.FirstRunPermissionDeviceTest', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=log, stderr=subprocess.STDOUT, timeout=180, check=True)
    Path('/tmp/sleepissue-permission-ui-result.json').write_text(adb('exec-out', 'run-as', package, '--user', user, 'cat', 'cache/first-run-permission-ui.json'))
finally:
    for permission, state in before.items():
        adb('shell', 'pm', 'grant' if state['granted'] else 'revoke', '--user', user, package, permission)
        adb('shell', 'pm', 'clear-permission-flags', '--user', user, package, permission, 'user-set', 'user-fixed')
        flags = [label for label, tag in [('user-set', 'USER_SET'), ('user-fixed', 'USER_FIXED')] if tag in state['flags']]
        if flags:
            adb('shell', 'pm', 'set-permission-flags', '--user', user, package, permission, *flags)
    print('원래 권한으로 복구했습니다.')
