"""SIG016 원시 elapsedRealtime 이벤트에서 시도별 최초 반영을 재계산한다."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parent

def analyze(name):
    data = json.loads((ROOT / f'device-{name}.json').read_text())
    events = sorted(data['events'], key=lambda x: x['atMs'])
    def first(kind, start, end, predicate):
        return next((e for e in events if e['kind'] == kind and start <= e['atMs'] < end and predicate(e['value'])), None)
    def state(v): return v['state']
    def active(v): return v is not None and v['remainTimeSec'] == 60 and v['remainDistM'] == 50
    def empty(v): return v is not None and v['remainTimeSec'] is None and v['remainDistM'] is None
    rows = []
    for a in data.get('attempts', []):
        b, end = a['requestBeforeMs'], a['cancelBeforeMs']
        adapter = first('adapter', b, end, lambda v: active(state(v)))
        latest = first('hubLatest', b, end, active)
        consumer = first('consumer1Hz', b, end, active)
        raw = first('sdkDestination', b, end, lambda v: v['remainTime'] == 60 and v['remainDistance'] == 50)
        route = first('sdkRoute', b, end, lambda v: v['naviMode'] == 'ROUTE_GUIDING')
        sdk = max(r['receivedMs'] for r in adapter['value']['readings']) if adapter else None
        row = {'attempt': a['attempt'], 'requestBracketMs': [b, a['requestAfterMs']],
               'startUiBracketMs': [a.get('startUiBeforeMs'), a.get('startUiAfterMs')],
               'startUiActionAccepted': a.get('startUiActionAccepted'),
               'sdkReceiptMs': sdk, 'rawDestinationCallbackMs': raw['atMs'] if raw else None,
               'rawGuidingCallbackMs': route['atMs'] if route else None,
               'adapterCollectedMs': adapter['atMs'] if adapter else None,
               'hubReflectionBracketMs': [latest['value']['tMs'], latest['atMs']] if latest else None,
               'consumerReceivedMs': consumer['atMs'] if consumer else None,
               'sensorObservations': [r['observations'] for r in adapter['value']['readings']] if adapter else None}
        if sdk and latest and consumer:
            row['requestToSdkMs'] = [sdk-a['requestAfterMs'], sdk-b]
            row['requestToHubMs'] = [latest['value']['tMs']-a['requestAfterMs'], latest['atMs']-b]
            row['requestToConsumerMs'] = [consumer['atMs']-a['requestAfterMs'], consumer['atMs']-b]
            row['sdkToAdapterMs'] = adapter['atMs']-sdk
            row['sdkToHubMs'] = [latest['value']['tMs']-sdk, latest['atMs']-sdk]
            row['sdkToConsumerMs'] = consumer['atMs']-sdk
            if a.get('startUiActionAccepted'):
                ub, ua = a['startUiBeforeMs'], a['startUiAfterMs']
                row['uiToSdkMs'] = [sdk-ua, sdk-ub]
                row['uiToHubMs'] = [latest['value']['tMs']-ua, latest['atMs']-ub]
                row['uiToConsumerMs'] = [consumer['atMs']-ua, consumer['atMs']-ub]
                # 두 Clock 읽기의 ms 양자화를 보수적으로 ±2ms로 적용해도 1초 초과인지 판정한다.
                row['wholeOneSecond'] = 'FAIL' if row['uiToHubMs'][0]-2 > 1000 else 'NOT_PROVEN'
        cb, ca = a['cancelBeforeMs'], a['cancelAfterMs']
        limit = data['attempts'][a['attempt']]['requestBeforeMs'] if a['attempt'] < len(data['attempts']) else float('inf')
        cancelled = first('sdkCancelled', cb, limit, lambda v: True)
        nulled = first('adapter', cb, limit, lambda v: empty(state(v)))
        hub = first('hubLatest', cb, limit, empty)
        received = first('consumer1Hz', cb, limit, empty)
        row['cancel'] = {'requestBracketMs': [cb, ca],
                         'sdkCallbackDelayMs': [cancelled['atMs']-ca, cancelled['atMs']-cb] if cancelled else None,
                         'adapterDelayMs': [nulled['atMs']-ca, nulled['atMs']-cb] if nulled else None,
                         'hubDelayMs': [hub['value']['tMs']-ca, hub['atMs']-cb] if hub else None,
                         'consumerDelayMs': [received['atMs']-ca, received['atMs']-cb] if received else None,
                         'nullReached': nulled is not None and hub is not None and received is not None}
        rows.append(row)
    return {'run': name, 'completed': data.get('completed'), 'clockQuantizationAllowanceMs': 2,
            'rows': rows, 'leaseReleased': data.get('leaseReleased'), 'ownerAfterRelease': data.get('ownerAfterRelease'),
            'hubAfterClose': data.get('hubAfterClose')}

if __name__ == '__main__':
    result = {name: analyze(name) for name in ['a', 'b']}
    (ROOT/'timing-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    for name, run in result.items():
        print(name)
        for r in run['rows']:
            print(r['attempt'], 'request→SDK/Hub/consumer', r.get('requestToSdkMs'), r.get('requestToHubMs'), r.get('requestToConsumerMs'),
                  'UI→SDK/Hub/consumer',r.get('uiToSdkMs'),r.get('uiToHubMs'),r.get('uiToConsumerMs'),
                  'SDK→adapter/Hub/consumer',r.get('sdkToAdapterMs'),r.get('sdkToHubMs'),r.get('sdkToConsumerMs'),'cancel',r['cancel'])
