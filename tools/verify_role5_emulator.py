"""에뮬레이터의 정식 6종을 재생하고 앱 자체 전달 감사·UI 결과를 보존한다."""
import argparse
import json
from pathlib import Path
import subprocess
import time

NAMES = ("normal", "distraction", "drowsy", "talk", "assist", "departure")
PACKAGE = "com.sleepissue.awakedrive"


def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=30)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("/tmp/awakedrive-role5-evidence/scenarios"))
    parser.add_argument("--speed", type=int, choices=(1, 10, 60), default=60)
    parser.add_argument("--names", nargs="+", choices=NAMES, default=NAMES)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    user = adb("shell", "am", "get-current-user").decode().strip()

    def private(*command):
        return adb("shell", "run-as", PACKAGE, "--user", user, *command)

    results = []
    for name in args.names:
        before = set(private("ls", "cache").decode().split())
        started = time.monotonic()
        adb("shell", "am", "start", "-S", "-W", "-n", PACKAGE + "/.debug.DebugHarnessActivity",
            "--es", "demoTransport", "COMPLETE_OBSERVATION", "--es", "demoScenarioName", name,
            "--ei", "demoSpeed", str(args.speed), "--ez", "demoAutoStart", "true", "--ez", "demoShowUi", "true")
        print(f"{name}: 재생 시작", flush=True)
        limit = 2710 / args.speed + 90
        new_file = None
        captures = set()
        while time.monotonic() - started < limit:
            elapsed = int(time.monotonic() - started)
            if elapsed in (20, 35, 45) and elapsed not in captures:
                captures.add(elapsed)
                (args.output / f"{name}-{elapsed}s.png").write_bytes(adb("exec-out", "screencap", "-p"))
            # 後半の P 段では休憩画面へ切り替え、走行中はアプリのロックに委ねる。
            if elapsed in (35, 40, 45) and elapsed not in captures:
                adb("shell", "input", "tap", "1555", "1268")
            files = set(private("ls", "cache").decode().split()) - before
            candidates = sorted(f for f in files if f.startswith("demo-perf-session-") and f.endswith(".jsonl"))
            if candidates:
                new_file = candidates[-1]
                break
            time.sleep(2)
        if new_file is None:
            raise RuntimeError(f"{name}: 종료 기록이 없거나 앱이 종료됐습니다")
        raw = private("cat", "cache/" + new_file)
        (args.output / f"{name}.jsonl").write_bytes(raw)
        rows = [json.loads(line) for line in raw.decode().splitlines()]
        summary = rows[0]["sessionSummaries"][-1]
        final = rows[-1]
        assert summary["finalPhase"] == "COMPLETED", summary
        assert final["deliveredSamples"] == 27100, final["deliveredSamples"]
        assert summary["unexpectedNullPublishedFrames"] == 0, summary
        audit = final["deliveryAudit"]
        for value in audit["channels"].values():
            for key in ("missingSamples", "remainingExpectedSamples", "duplicates", "outOfOrder", "unknownTimestamps"):
                assert value[key] == 0, (name, key, value[key])
            assert value["hash"] == audit["expectedHash"]
        result = {"scenario": name, "speed": args.speed, "summary": summary,
                  "actualProcessingElapsedMs": final.get("actualProcessingElapsedMs"),
                  "ui": rows[0].get("runtimeMetadata", {}).get("role5Ui"), "audit": audit}
        results.append(result)
        (args.output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
        (args.output / f"{name}-completed.png").write_bytes(adb("exec-out", "screencap", "-p"))
        print(f"{name}: 완료 · 27100개 · null/누락/중복/역전 0", flush=True)
    print(f"전체 {len(results)}종 완료: {args.output / 'results.json'}", flush=True)


if __name__ == "__main__":
    main()
