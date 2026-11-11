"""보존한 실제 부팅 원본을 읽어 판정한다. 파일·기기·DB를 수정하지 않는다."""
import collections
import gzip
import json
from pathlib import Path
import sys


def read(directory, name):
    path = directory / name
    if not path.exists():
        path = directory / (name + ".gz")
    data = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
    if name.endswith(".jsonl"):
        return [json.loads(line) for line in data.splitlines() if line.strip()]
    return json.loads(data)


def validate(directory):
    first = read(directory, "first-events-before-ui.jsonl")
    first_boot = max(event["bootCount"] for event in first)
    first = [event for event in first if event["bootCount"] == first_boot]
    counts = collections.Counter(event["stage"] for event in first)
    for stage in ("BOOT_RECEIVED", "START_REQUEST", "START_ACCEPTED", "SERVICE_CREATE", "INPUT_CREATED", "RUNTIME_STARTED"):
        assert counts[stage] == 1, (stage, counts)
    assert counts["START_COMMAND"] == 1
    command = next(event["data"] for event in first if event["stage"] == "START_COMMAND")
    assert command["action"] == "com.sleepissue.awakedrive.monitor.BOOT"
    stored = read(directory, "expected-snapshot.json")
    restored = next(event["data"] for event in first if event["stage"] == "RESTORED_BEFORE_INPUT")
    assert restored["restored"] == stored
    active = restored["active"]
    assert active["trip"]["driveState"] == "UNKNOWN"
    assert active["lastObservation"]["gear"] is None and active["lastObservation"]["power"] is None
    assert active["stopStartedAtMs"] is None and active["offStartedAtMs"] is None
    assert active["continuousDriveMs"] == stored["preciseState"]["continuousDriveMs"]
    assert active["driveSegments"] == stored["driveSegments"]
    assert not active["pendingNewTrip"] and not active["restResetEmitted"]
    # lastGear는 역사 필드다. 현재 gear로 오인해 null로 바꾸지 않는다.
    assert active["trip"]["lastGear"] == stored["state"]["lastGear"]
    running = next(event["data"] for event in first if event["stage"] == "RUNTIME_STARTED")
    assert running["starts"] == running["timers"] == 1
    fresh = next(event["data"] for event in first if event["stage"] == "FIRST_VALID_INPUT")
    assert fresh["activeBeforeAccept"]["trip"]["driveState"] == "UNKNOWN"
    assert fresh["activeBeforeAccept"]["lastObservation"]["gear"] is None
    full = read(directory, "final-events.jsonl")
    first_full = [event for event in full if event["bootCount"] == first_boot]
    for stage in ("START_REQUEST", "SERVICE_CREATE", "INPUT_CREATED", "RUNTIME_STARTED"):
        assert sum(event["stage"] == stage for event in first_full) == 1
    stopped = next(event["data"] for event in first_full if event["stage"] == "SERVICE_DESTROY")
    assert not stopped["running"] and stopped["timers"] == 0 and stopped["owner"] is None
    second = read(directory, "second-events-before-ui.jsonl")
    second_boot = max(event["bootCount"] for event in second)
    assert second_boot > first_boot
    second_counts = collections.Counter(event["stage"] for event in second if event["bootCount"] == second_boot)
    assert second_counts == {"BOOT_RECEIVED": 1, "REQUEST_SKIPPED": 1}
    database = read(directory, "second-analysis.json")
    assert database["databaseSha256BeforeSecondReboot"] == database["databaseSha256AfterSecondReboot"]
    assert len(set(read(directory, "boot-proof.json")["bootIds"])) == 3
    return {"checkpointEqual": True, "storedFieldCount": 10, "automaticStarts": 1,
            "afterExplicitStopStarts": 0, "activeUnknownBeforeValidInput": True}


if __name__ == "__main__":
    print(json.dumps(validate(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent), indent=2))
