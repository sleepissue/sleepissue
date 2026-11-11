"""동일 앱 원본을 재계산하고, 자원 표본과 시도 결과를 환경별로 묶는다."""
import json, statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def cpu(a,b):
    v=[y-x for x,y in zip(a[:8],b[:8])];total=sum(v)
    return {'busyPct':100*(total-v[3]-v[4])/total,'iowaitPct':100*v[4]/total} if total else None

def parse_guest(sample):
    raw=sample.get('raw','');out={'hostBeforeNs':sample.get('hostBeforeNs'),'hostAfterNs':sample.get('hostAfterNs')}
    for l in raw.splitlines():
        if l.startswith('cpu '):out['cpuTicks']=list(map(int,l.split()[1:]))
        elif l.startswith(('MemAvailable:','MemTotal:','SwapTotal:','SwapFree:')):out[l.split(':')[0]]=int(l.split()[1])
    if raw.splitlines():
        try:out['uptimeMs']=float(raw.splitlines()[0].split()[0])*1000
        except ValueError:pass
    return out

def span(v):return {'min':min(v),'median':statistics.median(v),'max':max(v),'count':len(v)} if v else None

def metrics(phase):
    r=json.loads((ROOT/phase/'resources.json').read_text());h=r['host'];g=[parse_guest(x) for x in r['guest']]
    cpu_stats=[cpu(a['cpuTicks'],b['cpuTicks']) for a,b in zip(h,h[1:])]
    total_cpu=cpu(h[0]['cpuTicks'],h[-1]['cpuTicks']);pc={}
    for a,b in zip(h,h[1:]):
        dt=(b['hostMonotonicNs']-a['hostMonotonicNs'])/1e9;old={x['pid']:x for x in a['processes']}
        for x in b['processes']:
            if x['pid'] in old and x['startTicks']==old[x['pid']]['startTicks']:
                pc.setdefault(x['role'],[]).append(100*(x['ticks']-old[x['pid']]['ticks'])/r['hostHz']/dt)
    guest_cpu=[cpu(a['cpuTicks'],b['cpuTicks']) for a,b in zip(g,g[1:]) if 'cpuTicks' in a and 'cpuTicks' in b]
    return {'phase':phase,'hostCpuBusyPct':span([x['busyPct'] for x in cpu_stats if x]),'hostWholeWindowCpu':total_cpu,
            'hostAvailableGiB':span([x['memory']['MemAvailable']/2**20 for x in h]),
            'hostSwapUsedGiB':span([(x['memory']['SwapTotal']-x['memory']['SwapFree'])/2**20 for x in h]),
            'swapInPages':h[-1]['vm']['pswpin']-h[0]['vm']['pswpin'], 'swapOutPages':h[-1]['vm']['pswpout']-h[0]['vm']['pswpout'],
            'majorFaultDelta':h[-1]['vm']['pgmajfault']-h[0]['vm']['pgmajfault'],
            'perProcessCpuPctOneCore100':{k:span(v) for k,v in pc.items()},
            'guestCpuBusyPct':span([x['busyPct'] for x in guest_cpu if x]),
            'guestAvailableGiB':span([x['MemAvailable']/2**20 for x in g if 'MemAvailable' in x]),
            'guestSwapUsedGiB':span([(x['SwapTotal']-x['SwapFree'])/2**20 for x in g if 'SwapTotal' in x]),
            'guestAdbDurationMs':span([(x['hostAfterNs']-x['hostBeforeNs'])/1e6 for x in g if x['hostBeforeNs']]),
            'indexEvents':(ROOT/phase/'index-events.txt').read_text().strip(),
            'buildEvents':(ROOT/phase/'build-events.txt').read_text().strip(),
            'traceStatus':[x for x in (ROOT/phase/'perfetto-after.txt').read_text().splitlines() if 'Tracing sessions:' in x]}

if __name__=='__main__':
    result={'resourceByRun':{},'byEnvironment':{},'allAttempts':[]}
    for phase in ['current1','current2','low1','low2']:
        result['resourceByRun'][phase]=metrics(phase)
        d=json.loads((ROOT/phase/'boundary-summary.json').read_text())
        for row in d['rows']:
            b,a=row['uiBracketMs'];q=row['firstDestinationCallAfterUiCompleted'];row['inputToNextDestinationCallMs']=[q['beginMs']-a,q['beginMs']-b];row['phase']=phase
            result['allAttempts'].append(row)
    for name,phases in [('current',['current1','current2']),('low',['low1','low2'])]:
        rows=[x for x in result['allAttempts'] if x['phase'] in phases]
        timers=[]
        for phase in phases:
            d=json.loads((ROOT/phase/'boundary-summary.json').read_text());timers +=[x['elapsedMs']-x['dueAtMs'] for x in d['allTimerResumptions']]
        result['byEnvironment'][name]={'attempts':len(rows),'timerLatenessMs':span(timers),'timerLatenessOver100Ms':sum(x>100 for x in timers),
            'firstDestinationCallDurationMs':span([r['firstDestinationCallAfterUiCompleted']['durationMs'] for r in rows]),
            'firstReturnToDestinationCallbackGapMs':span([r['firstPostUiReturnToFirstCallbackGapMs'] for r in rows]),
            'inputToNextDestinationCallEnvelopeMs':[min(r['inputToNextDestinationCallMs'][0] for r in rows),max(r['inputToNextDestinationCallMs'][1] for r in rows)],
            'sdkToHubEnvelopeMs':[min(r['sdkToHubMs'][0] for r in rows),max(r['sdkToHubMs'][1] for r in rows)],
            'inputToHubEnvelopeMs':[min(r['uiToHubMs'][0] for r in rows),max(r['uiToHubMs'][1] for r in rows)],
            'inputToConsumerEnvelopeMs':[min(r['uiToConsumerMs'][0] for r in rows),max(r['uiToConsumerMs'][1] for r in rows)],
            'postUiTimerLatenessMs':span([r['timerResumeLatenessMs'] for r in rows]),
            'allPublicSdkCallDurationMs':{op:span([c['durationMs'] for phase in phases for c in json.loads((ROOT/phase/'boundary-summary.json').read_text())['allPublicSdkCalls'] if c['operation']==op]) for op in ['location','route','destination','tbt']}}
    (ROOT/'comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    for name,v in result['byEnvironment'].items():print(name,json.dumps(v,ensure_ascii=False))
    for phase,v in result['resourceByRun'].items():
        print(phase,'CPU',v['hostWholeWindowCpu'],'RAMavail',v['hostAvailableGiB'],'swap',v['hostSwapUsedGiB'], 'qemuCPU',v['perProcessCpuPctOneCore100'].get('avd'),'studioCPU',v['perProcessCpuPctOneCore100'].get('studio'),'guestCPU',v['guestCpuBusyPct'])
