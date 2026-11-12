"""정식 DemoJsonLoader 계약의 6종 신호 합성. 숫자는 시연 입력이며 엔진 문턱값이 아니다."""
import argparse
import csv
import json
import math
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAMES = ("normal", "distraction", "drowsy", "talk", "assist", "departure")


def steering(name, t):
    normal = math.sin(2 * math.pi * .4 * t)
    if name == "departure":
        return .5 * math.sin(2 * math.pi * .2 * t) + (7 if t % 6 < .2 else 0)
    if name == "assist":
        if 10 <= t < 1220:
            return 0.0  # 해제 1210초 뒤 10초 동안 조향 고정
        if 1220 <= t < 1220.2:
            return 12.0
    if name == "talk" and 960 <= t < 970:
        return 0.0
    if name == "talk" and 970 <= t < 970.2:
        return 12.0
    if name == "distraction":
        for start in (960, 1200, 1500):
            # 1분 안에 회복하는 짧은 조작 공백 뒤 급조향. 최근 10분 중 이상 분은 3개 이하로 둔다.
            if start <= t < start + 25:
                return 0.0
            if start + 25 <= t < start + 25.2:
                return 12.0
    if name == "drowsy" and t >= 900:
        sleepy = t - 900
        event = sleepy % 7.5
        angle = .15 * math.sin(2 * math.pi * .05 * sleepy)
        if event < 3:
            angle += 7 * (event / 1.5 if event < 1.5 else (3 - event) / 1.5)
        return angle - (12 if sleepy % 150 < .2 else 0)
    return normal


def generate(name, factor):
    with (ROOT / "data/rest_areas/processed/rest_areas.csv").open() as stream:
        area = next(csv.DictReader(stream))
    lat, lon = float(area["lat"]), float(area["lon"])
    heading = math.radians(float(area["carriageway_bearing_deg"]))
    samples = []
    for index in range(27100):
        t = index / 10
        # 인증 기준은 바꾸지 않고 종료까지 P단을 유지해 기기 관측 지연의 여유를 둔다.
        parked = t < 10 or t >= 1800
        drive_t = min(1800, max(0, t))
        distance = max(0, 1800 - drive_t) * 98 / 3.6
        cruise = name == "assist" and 10 <= t < 1210
        samples.append({
            "t": t, "steeringDeg": 0.0 if parked else round(steering(name, t), 3),
            "speedKph": 0.0 if parked else 98.0, "gear": "P" if parked else "D",
            "turnSignal": "OFF", "brake": False,
            "seats": {"driver": True, "frontPassenger": name == "talk", "rearLeft": False,
                      "rearCenter": False, "rearRight": False},
            "cruise": {"active": cruise, "targetKph": 98.0 if cruise else None},
            "distToNextTurnM": 5000.0,
            "cabinTempC": 23.5,
            "lat": round(lat - distance * math.cos(heading) / 111320, 8),
            "lon": round(lon - distance * math.sin(heading) / (111320 * math.cos(math.radians(lat))), 8),
            "power": "OFF" if 1810 <= t < 2690 else "RESUME" if 2690 <= t < 2690.1 else "ON",
        })
    start = "2026-11-07T22:55:00+09:00" if name in ("drowsy", "departure") else "2026-11-07T10:00:00+09:00"
    return {"schemaVersion": 1, "id": name, "source": "DEMO",
            "startEpochMs": int(datetime.fromisoformat(start).timestamp() * 1000),
            "speedSegments": [{"fromSec": 0, "toSec": 1800, "factor": factor},
                              {"fromSec": 1800, "toSec": 2710, "factor": 60}],
            "samples": samples}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "data/scenarios")
    parser.add_argument("--factor", type=int, choices=(1, 10, 60), default=10)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for name in NAMES:
        scenario = generate(name, args.factor)
        (args.output / (name + ".json")).write_text(json.dumps(scenario, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(f"{name}: {len(scenario['samples'])} samples, schemaVersion=1")


if __name__ == "__main__":
    main()
