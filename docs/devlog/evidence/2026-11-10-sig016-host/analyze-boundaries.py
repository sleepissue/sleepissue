"""기존 SIG016 입력 bracket을 유지하며 호출/반환/콜백/예약 경계를 분리한다."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parent

def analyze():
    data = json.loads((ROOT/'device.json').read_text())
    points = sorted(data['naviTiming'], key=lambda x: x['elapsedMs'])
    events = sorted(data['events'], key=lambda x: x['atMs'])
    calls, pending = [], {}
    for p in points:
        if p['stage'] == 'CALL_BEGIN': pending[p['operation']] = p
        elif p['stage'] in ('CALL_RETURN', 'CALL_THROW'):
            start = pending.pop(p['operation'])
            calls.append({'operation': p['operation'], 'beginMs': start['elapsedMs'],
                          'returnMs': p['elapsedMs'], 'durationMs': p['elapsedMs']-start['elapsedMs'], 'endStage': p['stage']})
    rows = []
    for a in data['attempts']:
        b, after, end = a['startUiBeforeMs'], a['startUiAfterMs'], a['cancelBeforeMs']
        first = next(c for c in calls if after <= c['beginMs'] < end)
        dest = next(c for c in calls if c['operation'] == 'destination' and after <= c['beginMs'] < end)
        resume = max((p for p in points if p['stage'] == 'TIMER_RESUMED' and p['elapsedMs'] <= dest['beginMs']), key=lambda x: x['elapsedMs'])
        valid = next(p for p in points if p['stage'] == 'CALLBACK' and p['operation'] == 'destination' and p['seconds'] == 60 and p['meters'] == 50 and b <= p['elapsedMs'] < end)
        callback = next(p for p in points if p['stage'] == 'CALLBACK' and p['operation'] == 'destination' and dest['beginMs'] <= p['elapsedMs'] < end)
        route = next(p for p in points if p['stage'] == 'CALLBACK' and p['operation'] == 'route' and p['mode'] == 'ROUTE_GUIDING' and b <= p['elapsedMs'] < end)
        adapter = [p for p in points if p['operation'] == 'destination' and p['seconds'] == 60 and p['stage'] in ('ADAPTER_BEGIN','ADAPTER_END') and valid['elapsedMs'] <= p['elapsedMs'] < end][:2]
        hub = next(e for e in events if e['kind'] == 'hubLatest' and b <= e['atMs'] < end and e['value'] and e['value']['remainTimeSec'] == 60 and e['value']['remainDistM'] == 50)
        consumer = next(e for e in events if e['kind'] == 'consumer1Hz' and b <= e['atMs'] < end and e['value'] and e['value']['remainTimeSec'] == 60 and e['value']['remainDistM'] == 50)
        intervals = {'uiToSdkMs':[valid['elapsedMs']-after, valid['elapsedMs']-b],
                     'uiToHubMs':[hub['value']['tMs']-after,hub['atMs']-b],
                     'uiToConsumerMs':[consumer['atMs']-after,consumer['atMs']-b],
                     'sdkToHubMs':[hub['value']['tMs']-valid['elapsedMs'],hub['atMs']-valid['elapsedMs']],
                     'alreadyGuidingToHubMs':[hub['value']['tMs']-route['elapsedMs'],hub['atMs']-route['elapsedMs']]}
        rows.append({'attempt':a['attempt'], 'requestBracketMs':[a['requestBeforeMs'],a['requestAfterMs']],
                     'uiBracketMs':[b,after], 'uiActionAccepted':a['startUiActionAccepted'],
                     'firstCallAfterUiStarted':next(c for c in calls if b <= c['beginMs'] < end),
                     'firstCallAfterUiCompleted':first, 'firstDestinationCallAfterUiCompleted':dest,
                     'timerForFirstPostUiCycle':resume,
                     'timerPhaseRelativeToUiMs':[resume['dueAtMs']-after,resume['dueAtMs']-b],
                     'timerResumeLatenessMs':resume['elapsedMs']-resume['dueAtMs'],
                     'firstCallToDestinationPredecessorWorkMs':dest['beginMs']-first['beginMs'],
                     'firstDestinationCallbackAfterFirstPostUiQuery':callback,
                     'firstPostUiReturnToFirstCallbackGapMs':callback['elapsedMs']-dest['returnMs'],
                     'validDestinationCallback':valid,'alreadyGuidingCallback':route,
                     'adapterBoundaryPoints':adapter, 'hubReflectionBracketMs':[hub['value']['tMs'],hub['atMs']],
                     'consumerReceivedMs':consumer['atMs'],
                     'furtherDestinationCallsBeforeFirstCallback':[c for c in calls if c['operation']=='destination' and dest['beginMs'] < c['beginMs'] <= callback['elapsedMs']],
                     'inputOverlappingCalls':[c for c in calls if b <= c['beginMs'] < after], **intervals})
    return {'clock':'elapsedRealtime/ms, differences +/-2ms, original UI brackets preserved',
            'notARequestRtt':'callbacks have no request id; callback gap includes further calls',
            'rows':rows,'allPublicSdkCalls':calls,
            'maxPublicSdkCallDurationMs':{op:max(c['durationMs'] for c in calls if c['operation']==op) for op in ['location','route','destination','tbt']},
            'allTimerResumptions':[p for p in points if p['stage']=='TIMER_RESUMED'],
            'completed':data.get('completed'),'leaseReleased':data.get('leaseReleased'),
            'ownerAfterRelease':data.get('ownerAfterRelease'),'hubAfterClose':data.get('hubAfterClose')}

if __name__ == '__main__':
    result=analyze()
    (ROOT/'boundary-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    for r in result['rows']:
        b=r['uiBracketMs'][0]
        print(r['attempt'], 'first destination', [r['firstDestinationCallAfterUiCompleted'][k]-b for k in ['beginMs','returnMs']],
              'first callback',r['firstDestinationCallbackAfterFirstPostUiQuery']['elapsedMs']-b,
              'SDK→Hub',r['sdkToHubMs'],'UI→Hub',r['uiToHubMs'],'already-guiding→Hub',r['alreadyGuidingToHubMs'])
