#!/usr/bin/env python3
"""데모 trace를 한 세션만 분리해 분석한다. 원본 샘플과 Hub 발행 누락을 구분한다."""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
from typing import Any


def distribution(values: list[int | float]) -> dict[str, int | float] | None:
    if not values:
        return None
    ordered = sorted(values)
    def percentile(fraction: float) -> int | float:
        return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]
    return {"count": len(ordered), "min": ordered[0], "p50": percentile(.5),
            "p95": percentile(.95), "p99": percentile(.99), "max": ordered[-1]}


def analyze(records: list[dict[str, Any]], session_id: int) -> dict[str, Any]:
    # 파일 전역 summary 수치는 다른 세션을 포함하므로 의도적으로 사용하지 않는다.
    session_summaries = [session for record in records if record.get("type") == "summary"
                         for session in record.get("sessionSummaries", [])
                         if session.get("sessionId") == session_id]
    samples = [record for record in records
               if record.get("type") == "sample" and record.get("sessionId") == session_id]
    if not session_summaries and not samples:
        raise ValueError(f"세션 {session_id} 자료가 없습니다.")
    session = session_summaries[-1] if session_summaries else None
    playing = [sample for sample in samples if sample.get("phase") == "PLAYING"]
    checkpoints = [sample for sample in samples if isinstance(sample.get("deliveredSamples"), int)]
    checkpoint_decreases = sum(right["deliveredSamples"] < left["deliveredSamples"]
                               for left, right in zip(checkpoints, checkpoints[1:]))
    original_checkpoints = [sample["lastOriginalSampleMs"] for sample in samples
                            if isinstance(sample.get("lastOriginalSampleMs"), int)]
    original_decreases = sum(right < left for left, right in zip(original_checkpoints, original_checkpoints[1:]))
    emissions: dict[int, dict[str, Any]] = {}
    for sample in samples:
        sequence = sample.get("hubEmittedFrames")
        if isinstance(sequence, int) and isinstance(sample.get("hubPublishedAtElapsedMs"), int):
            emissions[sequence] = sample
    published = [emissions[sequence] for sequence in sorted(emissions)]
    adjacent_gaps = [right["hubPublishedAtElapsedMs"] - left["hubPublishedAtElapsedMs"]
                     for left, right in zip(published, published[1:])
                     if right["hubEmittedFrames"] == left["hubEmittedFrames"] + 1]
    missing_deadlines = [sample["hubSkippedDeadlines"] for sample in samples
                         if isinstance(sample.get("hubSkippedDeadlines"), int)]
    emitted = [sample["hubEmittedFrames"] for sample in samples
               if isinstance(sample.get("hubEmittedFrames"), int)]
    stale_evidence = []
    final_quality_counts: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    unexpected_fields: collections.Counter[str] = collections.Counter()
    for sample in playing:
        unexpected_fields.update(sample.get("unexpectedNullFields", []))
        for field, quality in sample.get("finalQuality", {}).items():
            if not isinstance(quality, dict):
                continue
            final_quality_counts[field][quality.get("availability", "UNKNOWN")] += 1
        demo_quality = sample.get("inputs", {}).get("DEMO", {}).get("quality", {})
        for field in sample.get("unexpectedNullFields", []):
            quality = demo_quality.get(field, {})
            if quality.get("availability") != "STALE":
                continue
            stale_evidence.append({"capturedElapsedMs": sample.get("capturedElapsedMs"),
                                   "scenarioNowMs": sample.get("scenarioNowMs"), "field": field,
                                   "inputDemoQuality": quality,
                                   "finalQuality": sample.get("finalQuality", {}).get(field),
                                   "lastOriginalSampleMs": sample.get("lastOriginalSampleMs"),
                                   "deliveredSamples": sample.get("deliveredSamples"),
                                   "hubEmittedFrames": sample.get("hubEmittedFrames"),
                                   "hubSkippedDeadlines": sample.get("hubSkippedDeadlines")})
    stale_evidence.sort(key=lambda evidence: (
        evidence["inputDemoQuality"].get("observedAgeMs") or 0,
        -(evidence["inputDemoQuality"].get("receiptAgeRealMs") or 0)), reverse=True)
    # 동일 사건의 여러 필드보다 서로 다른 관측 시각의 대표 사건을 선택한다.
    representative = []
    seen_capture = set()
    for evidence in stale_evidence:
        captured = evidence["capturedElapsedMs"]
        if captured not in seen_capture:
            representative.append(evidence)
            seen_capture.add(captured)
        if len(representative) == 8:
            break
    fresh_receipt_stale = [evidence for evidence in stale_evidence
                           if isinstance(evidence["inputDemoQuality"].get("receiptAgeRealMs"), (int, float))
                           and evidence["inputDemoQuality"]["receiptAgeRealMs"] <= 5]
    return {
        "sessionId": session_id,
        "sessionSummary": session,
        "detailRows": len(samples),
        "detailPlayingRows": len(playing),
        "firstDetailCapturedElapsedMs": samples[0].get("capturedElapsedMs") if samples else None,
        "lastDetailCapturedElapsedMs": samples[-1].get("capturedElapsedMs") if samples else None,
        "detailClassifications": dict(collections.Counter(sample.get("classification") for sample in samples)),
        "originalDelivery": {
            "deliveredSamplesMax": max((sample["deliveredSamples"] for sample in checkpoints), default=None),
            "lastOriginalSampleMsMax": max(original_checkpoints, default=None),
            "deliveredCheckpointDecreases": checkpoint_decreases,
            "originalTimeCheckpointDecreases": original_decreases,
            "originalLagScenarioMs": distribution([sample["originalLagScenarioMs"] for sample in playing
                                                     if isinstance(sample.get("originalLagScenarioMs"), (int, float))]),
            "independentAudit": next((sample["deliveryAudit"] for sample in reversed(samples)
                                      if sample.get("deliveryAudit") is not None), None),
            "sampleIntegrityEvidence": ("채널별 Hub 입력 Flow 인계 audit의 수·원본 시각 해시·누락·중복·역행을 함께 확인한다. Hub 차량 callback 수와도 대조한다."
                if any(sample.get("deliveryAudit") is not None for sample in samples)
                else "상세는 주기적으로만 보관된다. 체크포인트 단조성으로 원본별 순서·누락·중복을 입증할 수 없다."),
        },
        "hubPublication": {
            "emittedFramesMax": max(emitted, default=None),
            "skippedDeadlinesMax": max(missing_deadlines, default=None),
            "observedUniquePublicationSequences": len(emissions),
            "adjacentPublicationRealGapMs": distribution(adjacent_gaps),
            "note": "skippedDeadlines는 Hub 100ms 발행 시한 누락이며 원본 샘플 삭제 수가 아니다. 미보관 상세 행도 원본 누락으로 계산하지 않는다.",
        },
        "nullDetails": {
            "unexpectedNullFields": dict(unexpected_fields),
            "finalQualityAvailability": {field: dict(counts) for field, counts in final_quality_counts.items()},
            "staleDemoUnexpectedFieldRows": len(stale_evidence),
            "staleDemoUnexpectedFieldRowsWithReceiptAgeAtMost5Ms": len(fresh_receipt_stale),
            "representativeStaleEvidence": representative,
            "note": "세션 summary의 null tick은 모든 record 호출 집계다. 이 항목은 보관된 상세 행만 센다.",
        },
        "lastDetail": samples[-1] if samples else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--session", type=int, required=True, help="분리할 세션 ID; 전역 합산하지 않음")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--extract", type=Path, help="선택 세션 summary와 상세만 별도 JSONL로 저장")
    args = parser.parse_args()
    records = [json.loads(line) for line in args.trace.read_text(encoding="utf-8").splitlines() if line.strip()]
    try:
        result = analyze(records, args.session)
    except ValueError as error:
        parser.error(str(error))
    result["sourcePath"] = str(args.trace.resolve())
    result["sourceSha256"] = hashlib.sha256(args.trace.read_bytes()).hexdigest()
    content = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(content, encoding="utf-8")
    else:
        print(content, end="")
    if args.extract:
        summary = {"type": "summary", "scope": "SINGLE_SESSION_EXTRACT",
                   "sessionSummaries": [result["sessionSummary"]] if result["sessionSummary"] else []}
        selected = [record for record in records
                    if record.get("type") == "sample" and record.get("sessionId") == args.session]
        args.extract.write_text("".join(json.dumps(record, ensure_ascii=False) + "\n"
                                         for record in [summary, *selected]), encoding="utf-8")


if __name__ == "__main__":
    main()
