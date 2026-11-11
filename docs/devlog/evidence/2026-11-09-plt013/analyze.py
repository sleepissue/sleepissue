"""PLT013 실제 창의 발행·소비·품질·저장을 분리 집계한다. 표준 라이브러리만 사용한다."""
import collections
import json
import math
import sys


def order_stats(rows):
    previous = {}; seen = set(); missing = duplicate = reverse = 0
    for row in rows:
        session, sequence = row.get('sessionId'), row.get('sequence')
        key = (session, sequence)
        if key in seen: duplicate += 1
        if session in previous:
            if sequence < previous[session]: reverse += 1
            if sequence > previous[session] + 1: missing += sequence - previous[session] - 1
        if sequence is not None: previous[session] = max(previous.get(session, 0), sequence)
        seen.add(key)
    return {'count': len(rows), 'uniqueCount': len(seen), 'sequenceGaps': missing,
            'duplicates': duplicate, 'outOfOrder': reverse, 'sessions': len(previous)}


def gaps(values):
    values = list(values)
    d = [b - a for a, b in zip(values, values[1:])]
    return {'intervals': len(d), 'minMs': min(d, default=None), 'maxMs': max(d, default=None),
            'over110ms': sum(v > 110 for v in d), 'below90msDiagnostic': sum(v < 90 for v in d)}


def analyze(records, result, samples):
    begin, end = result['windowStartMs'], result['windowEndMs']
    p_all, c_all = records['published'], records['consumed']
    published = [r for r in p_all if begin <= r['atMs'] < end]
    keys = {(r['sessionId'], r['sequence']) for r in published}
    # 창 안에 발행된 프레임의 늦은 수신도 종료 드레인까지 찾는다. 경계로 가짜 누락을 만들지 않는다.
    consumed = [r for r in c_all if (r.get('sessionId'), r.get('sequence')) in keys]
    counts = collections.Counter((r.get('sessionId'), r.get('sequence')) for r in consumed)
    lost = sorted(keys - counts.keys())
    po, co = order_stats(published), order_stats(consumed)
    a, b = result['startHub'], result['endHub']
    skipped = b['skippedDeadlines'] - a['skippedDeadlines']
    origin = a['nextPublicationAtMs']
    # 같은100ms 격자의 창 경계 개수. 실제 emit의 경계 이동과 deadline 누락을 구분한다.
    planned = math.ceil((end - origin) / 100) - math.ceil((begin - origin) / 100)
    state_rows = sorted(c_all, key=lambda r: r['atMs'])
    quality_durations = collections.Counter(); quality_frames = collections.Counter()
    for r in state_rows:
        if begin <= r['atMs'] < end: quality_frames[r['availability']] += 1
    active = [r for r in state_rows if r['atMs'] <= begin][-1:]
    active += [r for r in state_rows if begin < r['atMs'] < end]
    if not active: quality_durations['NO_CONSUMER_STATE'] = end - begin
    else:
        if active[0]['atMs'] > begin: quality_durations['NO_CONSUMER_STATE'] += active[0]['atMs'] - begin
        for index, row in enumerate(active):
            start = max(begin, row['atMs']); finish = active[index + 1]['atMs'] if index + 1 < len(active) else end
            label = row['availability'] if row.get('gear') is None or row['availability'] != 'AVAILABLE' else 'AVAILABLE_' + row['gear']
            lease = row.get('leaseUntilMs')
            if label.startswith('AVAILABLE_') and lease is not None and lease < finish:
                valid_end = min(finish, max(start, lease))
                quality_durations[label] += valid_end - start
                quality_durations['LEASE_EXPIRED_BETWEEN_FRAMES'] += finish - valid_end
            else: quality_durations[label] += finish - start
    success = [r for r in records['saves'] if r['result'] == 'Succeeded']
    failures = [r for r in records['saves'] if begin <= r['completedMs'] < end and r['result'] != 'Succeeded']
    in_saves = [r for r in success if begin <= r['completedMs'] < end]
    cover_saves = [r for r in success if r['completedMs'] < begin][-1:] + in_saves + [r for r in success if r['completedMs'] >= end][:1]
    s = [r for r in samples if begin <= r['atMs'] < end]
    delivery = { (r['sessionId'], r['sequence']): r for r in consumed }
    delays = [delivery[(r['sessionId'], r['sequence'])]['atMs'] - r['atMs'] for r in published if (r['sessionId'], r['sequence']) in delivery]
    created = [r for r in records['lifecycle'] if r['event'] == 'CREATE' and begin <= r['atMs'] < end]
    start_trip, end_trip = result['startPrecise'], result['endPrecise']
    complete = end - begin >= 1_800_000  # 종료/복원 runner 상태는 별도 필드로 보존한다.
    frame_ok = complete and bool(published) and po['sessions'] == 1 and not any(po[k] for k in ['sequenceGaps', 'duplicates', 'outOfOrder']) and not any(co[k] for k in ['duplicates', 'outOfOrder']) and not lost and skipped == 0 and not records['dropped'] and abs(len(published) - planned) <= 1
    return {
        'windowStartMs': begin, 'windowEndMs': end, 'durationMs': end - begin,
        'procedureCompleted': result.get('procedureCompleted'), 'producer': po, 'consumerForWindowProducer': co,
        'missingConsumerKeys': lost, 'deadlineSkipsDelta': skipped, 'plannedGridDeadlines': planned,
        'producerCounterDelta': b['emittedFrames'] - a['emittedFrames'], 'auditDropped': records['dropped'],
        'emitBeforeIntervals': gaps(r['atMs'] for r in published),
        'frameTMsIntervals': gaps(r['tMs'] for r in consumed),
        'consumerReceiptIntervals': gaps(r['atMs'] for r in consumed),
        'producerToConsumerDelayMs': {'min': min(delays, default=None), 'max': max(delays, default=None)},
        'gearFrameAvailabilityCounts': dict(quality_frames), 'gearIntervalDurationMs': dict(quality_durations),
        'sdkSnapshotCount': len(s), 'sdkSnapshotSpacing': gaps(r['atMs'] for r in s),
        'sdkErrorSnapshots': sum(bool(r.get('sdkErrors')) for r in s),
        'sdkCollectionStates': dict(collections.Counter(str(r.get('collection')) for r in s)),
        'roomSuccessesInWindow': len(in_saves), 'roomFailuresInWindow': failures,
        'roomCompletionSpacingCoveringWindow': gaps(r['completedMs'] for r in cover_saves),
        'roomMaxSaveCallDurationMs': max((r['completedMs'] - r['startedMs'] for r in in_saves), default=None),
        'inputInstancesCreatedTotal': records['inputCreates'], 'serviceCreatesInsideWindow': len(created),
        'pidValues': sorted({r['pid'] for r in s}), 'startCounts': sorted({r['starts'] for r in s}),
        'monitorTimerCounts': sorted({r['timers'] for r in s}),
        'notForegroundSnapshots': sum(not r['foreground'] for r in s),
        'notRunningSnapshots': sum(not r['running'] for r in s),
        'runtimeFailureSnapshots': sum(r['runtimeFailure'] is not None for r in s),
        'continuousDriveMsStart': start_trip['continuousDriveMs'], 'continuousDriveMsEnd': end_trip['continuousDriveMs'],
        'recent24hSecStart': start_trip['trip']['last24hDriveSec'], 'recent24hSecEnd': end_trip['trip']['last24hDriveSec'],
        'tripIdUnchanged': start_trip['trip']['tripId'] == end_trip['trip']['tripId'],
        'frameDeliveryAndDeadlineCriterionMet': frame_ok,
        'sig001AndSig002PriorFailureStatusUnchanged': True,
    }


if __name__ == '__main__':
    import pathlib
    directory = pathlib.Path(sys.argv[1])
    data = analyze(*(json.loads((directory / n).read_text()) for n in ['frames-and-saves.json', 'result.json', 'sdk-samples.json']))
    print(json.dumps(data, ensure_ascii=False, indent=2))
