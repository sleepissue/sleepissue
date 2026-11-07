#!/usr/bin/env python3
"""데모 원본 감사와 독립 시작→처리 완료 시간으로 배속·세 시간축을 분리한다."""
from __future__ import annotations
import argparse,hashlib,json,math,re
from collections import Counter
from decimal import Decimal
from pathlib import Path

FIELDS={'SPEED_KPH':'speedKph','STEERING_DEG':'steeringDeg','GEAR':'gear','LAT':'lat','LON':'lon','POWER':'power'}

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def distribution(values):
    if not values:return None
    values=sorted(values)
    def pct(p):return values[max(0,math.ceil(len(values)*p)-1)]
    return {'count':len(values),'min':values[0],'p50':pct(.5),'p95':pct(.95),'p99':pct(.99),'max':values[-1]}
def payload_sha(samples):return hashlib.sha256(json.dumps(samples,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
def order_hash(times):
    value=0xcbf29ce484222325
    for timestamp in times:
        for byte in timestamp.to_bytes(8,'little',signed=True):value=((value^byte)*0x100000001b3)&0xffffffffffffffff
    return f'{value:016x}'
def require(condition,message):
    if not condition:raise ValueError(message)
def source_info(assets,scenario_id):
    path=assets/f'{scenario_id}.json';require(path.is_file(),f'원본 asset 없음: {path}')
    value=json.loads(path.read_text());samples=value['samples'];times=[]
    for sample in samples:
        ms=Decimal(str(sample['t']))*1000;require(ms==int(ms),'원본 시각 ms 정수가 아님');times.append(int(ms))
    require(times and times[0]==0 and all(b-a==100 for a,b in zip(times,times[1:])),'원본100ms격자/순서 오류')
    return {'path':str(path),'sha256':sha(path),'samplePayloadSha256':payload_sha(samples),'samples':samples,'times':times,'durationMs':times[-1]+100,'orderHash':order_hash(times),'speedSegments':value['speedSegments'],'timeSet':set(times),'byTime':dict(zip(times,samples))}
def question_events(samples):
    changes=[];previous=None
    for sample in samples:
        state=(sample.get('questionPhase'),sample.get('questionId'),sample.get('speedMultiplier'))
        if state!=previous:
            changes.append({key:sample.get(key) for key in ['capturedElapsedMs','scenarioNowMs','questionPhase','questionId','questionOutcome','speedMultiplier','playbackRealElapsedMs']})
        previous=state
    return changes

def analyze(meta_path,assets,manifest,source_cache):
    meta=json.loads(meta_path.read_text());trace_path=meta_path.with_name(meta_path.name.removesuffix('-run.json')+'.jsonl')
    require(trace_path.is_file(),'trace 없음');records=[json.loads(line) for line in trace_path.read_text().splitlines() if line.strip()]
    header=next(row for row in records if row.get('type')=='summary');session=max(header['sessionSummaries'],key=lambda s:s['sessionId']);sid=session['sessionId']
    require(sid==meta['sessionId'],'최신 session과sidecar 불일치')
    details=[row for row in records if row.get('type')=='sample' and row.get('sessionId')==sid];require(details,'선택 세션 상세없음');end=details[-1]
    require(session['finalPhase']==end['phase']=='COMPLETED','완료본 아님')
    scenario_id=end['scenarioId']
    if scenario_id not in source_cache:source_cache[scenario_id]=source_info(assets,scenario_id)
    source=source_cache[scenario_id];n=len(source['samples']);duration=source['durationMs']
    require(end['scenarioDurationMs']==duration,'duration 불일치');require(end['deliveredSamples']==n and end['lastOriginalSampleMs']==source['times'][-1],'원본 전달 개수/끝시각 불일치')
    if n==manifest['originalSamples']:
        require(duration==manifest['durationMs'] and source['samplePayloadSha256']==manifest['canonicalSampleSha256'],'manifest 원본 payload 불일치')
    audit=end['deliveryAudit'];require(audit['expectedTotal']==n and audit['expectedHash']==source['orderHash'],'예상 원본 수/순서hash 불일치')
    require(set(audit['channels'])=={'VEHICLE','NAVI','POWER'},'감사 채널 누락')
    for channel,stats in audit['channels'].items():
        require(stats['originalCount']==stats['uniqueExpectedCount']==n,channel+' 원본 개수')
        require(all(stats[key]==0 for key in ['missingSamples','remainingExpectedSamples','duplicates','outOfOrder','unknownTimestamps']),channel+' 누락/중복/역행')
        require(stats['hash']==source['orderHash'] and stats['invalidationCount']==1,channel+' hash/무효화')
        require(stats['firstOriginalMs']==source['times'][0] and stats['lastOriginalMs']==source['times'][-1],channel+' 첫끝시각')
    require(end['inputs']['DEMO']['vehicleCallbacks']==n+1,'Hub vehicle callback 불일치')
    require(end['inputs']['REAL']['vehicleCallbacks']==0,'가짜REAL off 조건 불일치')
    require(all(row['transportMode']=='COMPLETE_OBSERVATION' and row['workerBackend']=='DEFAULT_LANE' and row['sourcePreparation']=='LOAD_FROZEN' for row in details),'실행 경로/준비 불일치')
    require(not any(row.get('hubClockFailure') for row in details),'Hub clock failure')
    start=end.get('replayStartedAtElapsedMs');done=end.get('processingCompletedAtElapsedMs');require(isinstance(start,int) and isinstance(done,int) and done>start,'독립 실제 시작/완료 시각 없음')
    actual=done-start;require(end.get('actualProcessingElapsedMs')==actual,'실제 경과시각 차이 불일치')
    if meta.get('actualProcessingElapsedMs') is not None:require(meta['actualProcessingElapsedMs']==actual,'sidecar 실제 경과 불일치')
    require(done<=end['capturedElapsedMs'],'처리완료 시각이 capture보다 미래')
    factor=meta.get('factor')
    if factor is None:factor=source['speedSegments'][0]['factor']
    require(factor in [1,10,60],'지원 배속아님')
    require(all(segment['factor']==factor for segment in source['speedSegments']),'이 도구의 단일 요청배속 조건과 다름')
    is_short=n==manifest['originalSamples'];playing=[row for row in details if row['phase']=='PLAYING']
    has_question=any(row.get('questionId') is not None or row.get('questionPhase')=='GUIDANCE' for row in playing)
    if is_short:require(not has_question,'짧은 배속비교에 질문 개입')
    question_intervals=[];question_start=None
    for row in details:
        active=row.get('questionId') is not None and row.get('speedMultiplier')==1 and row['phase']=='PLAYING'
        if active and question_start is None:question_start=row['capturedElapsedMs']
        elif not active and question_start is not None:
            question_intervals.append((question_start,row['capturedElapsedMs']));question_start=None
    require(question_start is None,'질문 활성 상태로 끝난 완료본')
    question_hold=sum(end-start for start,end in question_intervals)
    require({row['replayStartedAtElapsedMs'] for row in details if row.get('replayStartedAtElapsedMs') is not None}=={start},'시작계측이 중간에 바뀜')
    require({row['processingCompletedAtElapsedMs'] for row in details if row.get('processingCompletedAtElapsedMs') is not None}=={done},'완료계측이 중간에 바뀜')
    violations=Counter();checks=Counter();last_observed={};last_sequence={};axes=[];rates=[];after_horizon=[];post_horizon_no_pub=[]
    for row in details:
        elapsed=row['capturedElapsedMs']-start
        if elapsed<0:continue
        clock_target=row['scenarioNowMs'];watermark=row.get('lastOriginalSampleMs');coverage=None if watermark is None else min(duration,watermark+100)
        expected_no_question=min(duration,max(0,elapsed*factor))
        question_overlap=sum(max(0,min(row['capturedElapsedMs'],end)-begin) for begin,end in question_intervals)
        expected_with_question=min(duration,max(0,elapsed*factor-question_overlap*(factor-1)))
        candidates=row.get('inputs',{}).get('DEMO',{}).get('quality',{});candidate=candidates.get('STEERING_DEG',{})
        selected=row.get('finalQuality',{}).get('STEERING_DEG',{})
        hub_frame=row.get('hubFrameTimeMs')
        axes.append({'capturedElapsedMs':row['capturedElapsedMs'],'actualElapsedSinceStartMs':elapsed,'phase':row['phase'],'requestedFactor':factor,'effectiveMultiplierReported':row.get('speedMultiplier'),'independentIdealTargetWithoutQuestionMs':expected_no_question,'independentQuestionAdjustedTargetEstimateMs':expected_with_question,'rawTargetAliasMs':row.get('targetScenarioMs'),'rawHandoffAliasMs':row.get('handoffCompletedScenarioMs'),'reportedClockScenarioTargetMs':clock_target,'pumpTargetMs':row.get('pumpTargetMs'),'pumpFinishedScenarioMs_CLOCK_NOT_WATERMARK':row.get('pumpFinishedScenarioMs'),'flowHandoffWatermarkObservedMs':watermark,'handoffCoverageEndMs_DERIVED_NOT_OBSERVATION':coverage,'deliveredSamples':row['deliveredSamples'],'hubPublishedScenarioMs':hub_frame,'hubNextPublicationAtMs':row.get('hubNextPublicationAtMs'),'hubPublicationTimeBasis':row.get('hubPublicationTimeBasis'),'hubEmittedFrames':row['hubEmittedFrames'],'hubSkippedDeadlines':row['hubSkippedDeadlines'],'hubSelectedSteeringObservedAtMs':selected.get('observedAtMs') if selected.get('source')=='DEMO' else None,'hubDemoCandidateSteeringObservedAtMs_AT_DIAGNOSTIC_SNAPSHOT':candidate.get('observedAtMs'),'reportedClockMinusHandoffObservedMs':None if watermark is None else clock_target-watermark,'idealTargetMinusHandoffCoverageMs':None if coverage is None else expected_no_question-coverage,'clockTargetMinusHubScenarioMs':None if hub_frame is None else clock_target-hub_frame,'rawTimeAxisModeMarker':row.get('timeAxisMode')})
        if clock_target>=duration and row['deliveredSamples']<n:after_horizon.append(row)
        if 'targetScenarioMs' in row:require(row['targetScenarioMs']==row['playbackScenarioMs'],'target 별칭 불일치')
        if 'handoffCompletedScenarioMs' in row:require(row['handoffCompletedScenarioMs']==row['lastOriginalSampleMs'],'handoff 별칭 불일치')
        if row['phase']!='PLAYING':continue
        rate=row.get('inputs',{}).get('DEMO',{}).get('steeringRate')
        if rate:rates.append(rate)
        for field,q in candidates.items():
            if not isinstance(q,dict) or q.get('observedAtMs') is None or q.get('availability') not in ['AVAILABLE','STALE']:continue
            at=q['observedAtMs'];checks['originalQualityTimestampChecks']+=1
            if q.get('source')!='DEMO' or q.get('observedTimeBasis')!='SCENARIO':violations['sourceOrTimeBasis']+=1
            if at not in source['timeSet']:violations['notOriginalTimestamp']+=1
            if at>clock_target:violations['futureObservedTime']+=1
            if field in last_observed and at<last_observed[field]:violations['reversedObservedTime']+=1
            last_observed[field]=at;sequence=q.get('observationSequence')
            if sequence is not None:
                if field in last_sequence and sequence<last_sequence[field]:violations['reversedObservationSequence']+=1
                last_sequence[field]=sequence
        for field,key in FIELDS.items():
            q=row.get('finalQuality',{}).get(field,{})
            if q.get('source')!='DEMO' or q.get('availability')!='AVAILABLE':continue
            at=q.get('observedAtMs');checks['availableValueOriginalChecks']+=1
            if at not in source['timeSet']:violations['availableValueUnknownObservedTime']+=1;continue
            expected=source['byTime'][at].get(key);value=row.get('finalValues',{}).get(key)
            equal=isinstance(value,(int,float)) and abs(value-expected)<=1e-5 if isinstance(expected,(int,float)) and not isinstance(expected,bool) else expected==value
            if not equal:violations['valueDoesNotMatchObservedOriginal']+=1
    for before,after in zip(axes,axes[1:]):
        if before['reportedClockScenarioTargetMs']>=duration and after['reportedClockScenarioTargetMs']>=duration and after['deliveredSamples']>before['deliveredSamples'] and after['hubEmittedFrames']==before['hubEmittedFrames']:
            post_horizon_no_pub.append({'before':before,'after':after})
    require(not violations,'시간축/원본 값 위반: '+str(dict(violations)))
    frame_rows=[row for row in details if row.get('hubFrameTimeMs') is not None];last_hub=frame_rows[-1] if frame_rows else None
    expected_real=duration/factor;achieved=duration/actual;clock_elapsed=end.get('playbackRealElapsedMs');hz=(n-1)*1000/(source['times'][-1]-source['times'][0]) if n>1 else None
    runtime=header.get('runtimeMetadata',{});markers=sorted({row['timeAxisMode'] for row in details if row.get('timeAxisMode') is not None});variant=meta.get('variant') or meta.get('timeAxisMode') or 'WALL_TARGET'
    if markers:require(all(marker==variant for marker in markers),'raw timeAxisMode와 sidecar불일치')
    end_rate=end.get('inputs',{}).get('DEMO',{}).get('steeringRate');source_rate_checks={'basisValues':sorted({r.get('timeBasis') for r in rates if r.get('timeBasis') is not None}),'measuredHz':distribution([r['hz'] for r in rates if r.get('status')=='MEASURED' and isinstance(r.get('hz'),(int,float))]),'final':end_rate}
    if end_rate:require(end_rate['acceptedObservations']==n and end_rate['duplicateObservations']==end_rate['reversedObservations']==0,'원천Rate 관측계수 불일치')
    emitted=end['hubEmittedFrames'];missed=end['hubSkippedDeadlines'];repetition=meta.get('repetition','UNKNOWN')
    automatic=trace_path.with_name(trace_path.stem+'-automatic.jsonl');auto=None
    if automatic.exists():
        ar=[json.loads(line) for line in automatic.read_text().splitlines() if line.strip()];asummary=next(r for r in ar if r.get('type')=='summary')['sessionSummaries'][-1]
        require(asummary['sessionId']==sid and asummary['finalPhase']=='COMPLETED','자동종료본 session/phase 불일치');adetails=[r for r in ar if r.get('type')=='sample' and r.get('sessionId')==sid];require(adetails[-1]['deliveredSamples']==n and adetails[-1].get('actualProcessingElapsedMs')==actual,'자동종료본 원본수/실제시간 불일치');auto={'path':str(automatic),'sha256':sha(automatic),'sessionId':sid,'finalPhase':'COMPLETED'}
    indices=set(range(min(3,len(axes))))|set(range(max(0,len(axes)-3),len(axes)))
    if axes:
        with_watermark=[(index,p) for index,p in enumerate(axes) if p['reportedClockMinusHandoffObservedMs'] is not None]
        if with_watermark:indices.add(max(with_watermark,key=lambda pair:pair[1]['reportedClockMinusHandoffObservedMs'])[0])
        for index,point in enumerate(axes):
            if point['reportedClockScenarioTargetMs']>=duration:indices.add(index);break
    representative=[axes[index] for index in sorted(indices)]
    adjusted_expected=expected_real+question_hold*(1-1/factor)
    return {'label':meta_path.name.removesuffix('-run.json'),'variant':variant,'condition':meta.get('condition','speed-only'),'speedClass':'low' if factor in [1,10] else 'high','requestedFactor':factor,'repetition':repetition,'sessionId':sid,'devicePid':meta.get('devicePid') or meta.get('environmentBefore',{}).get('devicePid'),'tracePath':str(trace_path),'traceSha256':sha(trace_path),'runMetadataPath':str(meta_path),'automaticTrace':auto,'scenarioId':scenario_id,'source':{k:source[k] for k in ['path','sha256','samplePayloadSha256','durationMs','orderHash']},'originalSamples':n,'originalRangeMs':[source['times'][0],source['times'][-1]],'sourceOriginalHz':hz,'deliveryAudit':audit,'hubDemoVehicleCallbacks':end['inputs']['DEMO']['vehicleCallbacks'],'strictDataContractPassed':True,'checks':dict(checks),'violations':dict(violations),'variantMarkerVerified':bool(markers),'variantProvenance':'raw timeAxisMode' if markers else 'sidecar variant + root가 확인한 동일 WALL_TARGET 측정APK. 해당시점 raw marker 미도입.','actualTiming':{'replayStartedAtElapsedMs':start,'processingCompletedAtElapsedMs':done,'actualStartToProcessingCompleteMs':actual,'elapsedSource':'Controller realClock.elapsedRealtimeMs 시작 명령 진입→COMPLETED 최초 관찰. DemoClock.realElapsed를 사용하지 않음.','resolutionMs':1,'completionScope':'Player가 원본 전달·duration gate·무효화를 마치고 COMPLETED가 된 것을 Controller publish가 최초 관찰한 시점. 마지막 Hub원본 cache완료 나노초를 직접 잰 값은 아님.'},'requestedVsAchieved':{'idealCompletionRealMsWithoutQuestion':expected_real,'actualCompletionRealMs':actual,'wholeReplayAchievedFactor':achieved,'achievedDividedByRequested':achieved/factor,'relativeSpeedErrorPercent':100*(achieved/factor-1),'completionOverrunMsVsNoQuestionIdeal':actual-expected_real,'wholeRunOriginalThroughputRealHz':n*1000/actual,'clockReportedRealElapsedMs_REFERENCE_ONLY':clock_elapsed,'actualMinusClockReportedElapsedMs':None if clock_elapsed is None else actual-clock_elapsed,'hasQuestion':has_question,'questionActiveRealMsFromTransitionRows':question_hold,'questionAdjustedExpectedRealMsEstimate':adjusted_expected,'questionAdjustedExpectedWholeFactorEstimate':duration/adjusted_expected,'actualMinusQuestionAdjustedExpectedMsEstimate':actual-adjusted_expected,'questionIntervalsCapturedElapsedMs':question_intervals,'questionEstimateNote':'독립 capture 전환시각으로 만든 근사치이며 clock.setQuestionActive의 내부 나노초를 직접 잰 값은 아님.','questionCaveat':'질문이 있으면 no-question ideal은 성능 판정 기준이 아님. 질문1배 의도적 연장과 별도 비교 필요.' if has_question else None},'sessionSummary':session,'hubPublication':{'emittedFrames':emitted,'skippedDeadlines':missed,'emittedPlusSkippedDeadlines':emitted+missed,'nominalDeadlinesForDuration':duration//100,'unpublishedTailDeadlines':duration//100-emitted-missed,'lastSampledHubPublishedScenarioMs':None if last_hub is None else last_hub['hubFrameTimeMs'],'lastSampledPublicationElapsedMs':None if last_hub is None else last_hub.get('hubPublishedAtElapsedMs'),'lastSampledDemoCandidateObservedAtMs':None if last_hub is None else last_hub.get('inputs',{}).get('DEMO',{}).get('quality',{}).get('STEERING_DEG',{}).get('observedAtMs')},'targetVsWatermarkVsHub':{'axisDefinitions':{'independentIdealTarget':'외부 실제 경과×요청배속, duration에서 clamp. 질문이 있을 때는 무질문 참고축.','reportedClockScenarioTarget':'질문 등이 반영된 Clock의 scenarioNowMs. 현재 위치 주장이지 처리 완료 watermark가 아님.','flowHandoffWatermark':'lastOriginalSampleMs. 원본 관측 시각이며 Clock을 따라 새로 찍지 않음.','handoffCoverageEnd':'watermark+원본100ms interval. 분석 파생값이며 observedAt을 덮어쓰지 않음.','hubScenario':'실제 발행 frame.tMs. 마지막 원본 observedAt과 다른 축.','hubCacheWatermarkLimit':'최종N+1 callback은 독립 확인. 중간 원본별 cache완료 시각/watermark는 이 trace가 직접 제공하지 않음.'},'sampledAxisPointCount':len(axes),'representativeAxisPoints':representative,'handoffObservedLagScenarioMs':distribution([p['reportedClockMinusHandoffObservedMs'] for p in axes if p['reportedClockMinusHandoffObservedMs'] is not None]),'rowsAtTargetHorizonWithOriginalsPending':len(after_horizon),'firstSampledHorizonPending':None if not after_horizon else {k:after_horizon[0].get(k) for k in ['capturedElapsedMs','scenarioNowMs','deliveredSamples','lastOriginalSampleMs','hubEmittedFrames','hubNextPublicationAtMs']},'observedDrainAfterTargetHorizonMsLowerBound':None if not after_horizon else done-after_horizon[0]['capturedElapsedMs'],'continuedOriginalDeliveryWithoutNewHubPublicationAfterHorizonPairs':len(post_horizon_no_pub),'representativeAfterHorizonPairs':post_horizon_no_pub[:2],'sourceRateReported':source_rate_checks},'questionTransitions':question_events(details),'runtimeMetadata':runtime,'environment':{'before':meta.get('environmentBefore'),'hostFinishedAt':meta.get('hostFinishedAt'),'hostElapsedNotUsed':'호스트 수집 완료는 대기·수동 저장 지연을 포함하므로 실제 재생시간으로 쓰지 않음.'},'limitations':['상세는 실제100ms/상태변화 보관이라 모든 원본별 지연 분포를 복원할 수 없다. 원본별 순서/누락은 독립 audit로 검사했다.','start/complete stamp는 독립 실제1ms clock이지만 Domain Clock 시작·Hub마지막 cache완료를 나노초 단위로 직접 잰 값은 아니다.','pumpFinishedScenarioMs는 Clock 위치이며 처리 watermark가 아니다.','성능허용오차가 지정되지 않아 달성배속의 수치를 보고하며 임의 pass 임계값을 만들지 않는다.']}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report-dir',type=Path,required=True);parser.add_argument('--assets-dir',type=Path,default=Path('src/android/app/src/debug/assets'));parser.add_argument('--manifest',type=Path);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--variant');args=parser.parse_args()
    manifest_path=args.manifest or args.report_dir/'short-scenario-manifest.json';manifest=json.loads(manifest_path.read_text());sources={};runs=[];invalid=[]
    required_labels={f'wall_target-short-{factor}x-{rep}' for factor in [1,10,60] for rep in ['first','repeat']}|{f'final-wall-long-complete-{condition}-{rep}' for condition in ['normal','cancel'] for rep in ['first','repeat']}
    ignored=[]
    for path in sorted(args.report_dir.glob('*-run.json')):
        if path.name.removesuffix('-run.json') not in required_labels:ignored.append(str(path));continue
        meta=json.loads(path.read_text())
        if args.variant and meta.get('variant','WALL_TARGET')!=args.variant:continue
        try:runs.append(analyze(path,args.assets_dir,manifest,sources))
        except (ValueError,KeyError,TypeError,StopIteration) as error:invalid.append({'metadataPath':str(path),'error':str(error),'valid':False})
    pairs=[]
    for variant in sorted({r['variant'] for r in runs}):
        for duration in sorted({r['source']['durationMs'] for r in runs if r['variant']==variant}):
            for factor,condition in sorted({(r['requestedFactor'],r['condition']) for r in runs if r['variant']==variant and r['source']['durationMs']==duration}):
                group=[r for r in runs if r['variant']==variant and r['source']['durationMs']==duration and r['requestedFactor']==factor and r['condition']==condition]
                first=next((r for r in group if r['repetition']=='first'),None);repeat=next((r for r in group if r['repetition']=='repeat'),None)
                if first and repeat:pairs.append({'variant':variant,'durationMs':duration,'factor':factor,'condition':condition,'sameProcess':first['devicePid']==repeat['devicePid'],'differentSession':first['sessionId']!=repeat['sessionId'],'firstActualMs':first['actualTiming']['actualStartToProcessingCompleteMs'],'repeatActualMs':repeat['actualTiming']['actualStartToProcessingCompleteMs'],'firstAchievedFactor':first['requestedVsAchieved']['wholeReplayAchievedFactor'],'repeatAchievedFactor':repeat['requestedVsAchieved']['wholeReplayAchievedFactor']})
    result={'measurement':'INDEPENDENT_COMPLETION_AND_SEPARATE_TIME_AXES','status':'STRICT_DATA_ERRORS' if invalid else 'REQUIRED_TEN_COMPLETE' if len(runs)==10 else 'COLLECTING','manifestPath':str(manifest_path),'manifest':manifest,'runs':runs,'invalidRuns':invalid,'requiredLabels':sorted(required_labels),'ignoredOutOfScopeMetadata':ignored,'requiredRunCount':10,'validRequiredRunCount':len(runs),'firstRepeatPairs':pairs,'lowSpeedRuns':[r['label'] for r in runs if r['speedClass']=='low'],'highSpeedRuns':[r['label'] for r in runs if r['speedClass']=='high'],'notes':['기존376 장기실험 및 transport.json과 분리한 코호트.','달성배속은 actual start→처리COMPLETED 감지 경과를 분모로 하며 clamp된DemoClock.realElapsed는 참조만 한다.','생산 시간축·TTL·원본시각·Frame알고리즘을 이 분석기가 수정하지 않는다.']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    if args.output.exists():
        previous=json.loads(args.output.read_text());result=previous|result  # root 보조증거 필드를 보존한다.
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(f'valid={len(runs)} invalid={len(invalid)} output={args.output}')
    for run in runs:print(run['label'],'actualMs',run['actualTiming']['actualStartToProcessingCompleteMs'],'achieved',round(run['requestedVsAchieved']['wholeReplayAchievedFactor'],6),'nullPublished',run['sessionSummary'].get('unexpectedNullPublishedFrames'),'Hub',run['hubPublication']['emittedFrames'],run['hubPublication']['skippedDeadlines'])
    for error in invalid:print('INVALID',error)
    if invalid:raise SystemExit(2)

if __name__=='__main__':main()
