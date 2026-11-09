import json
import math
from pathlib import Path

# 프로젝트 최상위 폴더
ROOT = Path(__file__).resolve().parents[2]

# JSON 저장 위치
OUTPUT_DIR = ROOT / "data" / "scenarios"


def generate_normal():
    """
    정상 주행 데모 시나리오 생성

    - 30분 주행
    - 100ms 간격 (10Hz)
    - 작은 조향 움직임
    - 큰 조향 움직임 없음
    """

    samples = []

    duration_sec = 1800
    interval_ms = 100

    for t_ms in range(0, duration_sec * 1000, interval_ms):

        t_sec = t_ms / 1000.0

        # 정상 주행 조향 신호
        steering = math.sin(2 * math.pi * 0.4 * t_sec)

        # 차량 속도
        speed = 98.0

        # 다음 회전 지점까지 거리
        distance = max(
            0.0,
            80000.0 - speed / 3.6 * t_sec
        )

        sample = {
            "tMs": t_ms,
            "steeringDeg": round(steering, 3),
            "speedKph": speed,
            "gear": "D",
            "turnSignal": "OFF",
            "brake": False,
            "seats": {
                "driver": True,
                "frontPassenger": False,
                "rearLeft": False,
                "rearCenter": False,
                "rearRight": False
            },
            "cruise": {
                "active": False,
                "targetKph": None
            },
            "distToNextTurnM": round(distance, 1),
            "cabinTempC": 23.5
        }

        samples.append(sample)

    scenario = {
        "formatVersion": 1,
        "scenarioId": "normal",
        "title": "정상 주행 시나리오",
        "startAt": "2026-11-07T14:00:00+09:00",
        "speedSegments": [
            {
                "fromSec": 0,
                "toSec": 1800,
                "factor": 10
            }
        ],
        "samples": samples
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    output_file = OUTPUT_DIR / "normal.json"

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(
            scenario,
            f,
            ensure_ascii=False,
            separators=(",", ":")
        )

    print("정상 주행 시나리오 생성 완료!")
    print("저장 위치:", output_file)
    print("샘플 개수:", len(samples))
    print("첫 번째 시각:", samples[0]["tMs"], "ms")
    print("마지막 시각:", samples[-1]["tMs"], "ms")


def generate_drowsy():
    """
    졸음운전 데모 시나리오 생성.

    처음 15분: 정상 주행
    이후 15분: 졸음 의심 조향 패턴

    실제 졸음 판단 결과는 엔진 테스트로 검증해야 한다.
    """

    samples = []

    duration_sec = 1800
    interval_ms = 100
    speed = 98.0

    for t_ms in range(0, duration_sec * 1000, interval_ms):

        t_sec = t_ms / 1000.0

        if t_sec < 900:
            # 처음 15분: 정상 주행
            steering = math.sin(
                2 * math.pi * 0.4 * t_sec
            )

        else:
            # 졸음 구간 시작 후 경과 시간
            sleepy_sec = t_sec - 900

            # 기본적으로 조향 변화가 적은 상태
            steering = 0.15 * math.sin(
                2 * math.pi * 0.05 * sleepy_sec
            )

            # 7.5초마다 부드러운 큰 조향 변화
            # 3초 동안 서서히 꺾었다가 복귀
            # 급격한 6도 이상 변화와 구분
            large_event = sleepy_sec % 7.5

            if large_event < 3.0:
                if large_event < 1.5:
                    steering += 7.0 * (large_event / 1.5)
                else:
                    steering += 7.0 * ((3.0 - large_event) / 1.5)

            # 150초마다 급조향
            # 약 2분 30초 간격
            rapid_event = sleepy_sec % 150

            if rapid_event < 0.2:
                steering -= 12.0

        distance = max(
            1000.0,
            80000.0 - speed / 3.6 * t_sec
        )

        sample = {
            "tMs": t_ms,
            "steeringDeg": round(steering, 3),
            "speedKph": speed,
            "gear": "D",
            "turnSignal": "OFF",
            "brake": False,

            "seats": {
                "driver": True,
                "frontPassenger": False,
                "rearLeft": False,
                "rearCenter": False,
                "rearRight": False
            },

            "cruise": {
                "active": False,
                "targetKph": None
            },

            "distToNextTurnM": round(distance, 1),
            "cabinTempC": 23.5
        }

        samples.append(sample)

    scenario = {
        "formatVersion": 1,
        "scenarioId": "drowsy",
        "title": "정상 주행 후 졸음 발생",
        "startAt": "2026-11-07T22:55:00+09:00",

        "tripPreset": {
            "continuousDriveSec": 7800,
            "last24hDriveSec": 7800
        },

        "speedSegments": [
            {
                "fromSec": 0,
                "toSec": 900,
                "factor": 10
            },
            {
                "fromSec": 900,
                "toSec": 1800,
                "factor": 1
            }
        ],

        "samples": samples
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    output_file = OUTPUT_DIR / "drowsy.json"

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(
            scenario,
            f,
            ensure_ascii=False,
            separators=(",", ":")
        )

    print("졸음운전 시나리오 생성 완료!")
    print("저장 위치:", output_file)
    print("샘플 개수:", len(samples))
    print("첫 번째 시각:", samples[0]["tMs"], "ms")
    print("마지막 시각:", samples[-1]["tMs"], "ms")


def generate_distraction():
    """
    딴짓 운전 시나리오

    0~15분 10초: 정상 주행
    15분 10초~15분 45초: 조향값 고정
    15분 45초: 급조향 발생
    이후: 정상 주행 복귀

    실제 엔진 판정은 별도 검증 필요
    """

    samples = []

    duration_sec = 1800
    interval_ms = 100
    speed = 98.0

    distraction_start = 910.0
    distraction_end = 945.0

    # 조향 정지 직전의 각도를 유지
    held_steering = round(
        math.sin(2 * math.pi * 0.4 * distraction_start),
        3
    )

    for t_ms in range(0, duration_sec * 1000, interval_ms):

        t_sec = t_ms / 1000.0

        if t_sec < distraction_start:
            steering = math.sin(2 * math.pi * 0.4 * t_sec)

        elif t_sec < distraction_end:
            # 35초간 조향값 고정
            steering = held_steering

        elif t_sec < distraction_end + 0.2:
            # 0.2초 안에 9도 급조향
            steering = held_steering + 9.0

        else:
            # 정상 주행 복귀
            steering = math.sin(2 * math.pi * 0.4 * t_sec)

        distance = max(
            1000.0,
            80000.0 - speed / 3.6 * t_sec
        )

        sample = {
            "tMs": t_ms,
            "steeringDeg": round(steering, 3),
            "speedKph": speed,
            "gear": "D",
            "turnSignal": "OFF",
            "brake": False,
            "seats": {
                "driver": True,
                "frontPassenger": False,
                "rearLeft": False,
                "rearCenter": False,
                "rearRight": False
            },
            "cruise": {
                "active": False,
                "targetKph": None
            },
            "distToNextTurnM": round(distance, 1),
            "cabinTempC": 23.5
        }

        samples.append(sample)

    scenario = {
        "formatVersion": 1,
        "scenarioId": "distraction",
        "title": "운전 중 딴짓 및 급조향",
        "startAt": "2026-11-07T14:00:00+09:00",
        "speedSegments": [
            {
                "fromSec": 0,
                "toSec": 900,
                "factor": 10
            },
            {
                "fromSec": 900,
                "toSec": 1800,
                "factor": 1
            }
        ],
        "samples": samples
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    output_file = OUTPUT_DIR / "distraction.json"

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(
            scenario,
            f,
            ensure_ascii=False,
            separators=(",", ":")
        )

    print("딴짓 운전 시나리오 생성 완료!")
    print("저장 위치:", output_file)
    print("샘플 개수:", len(samples))


def generate_talk():
    """
    동승자 대화 시나리오.

    정상 주행과 동일한 차량 조향 패턴을 사용한다.
    대화 인식은 3번 팀원의 기능이 담당한다.
    """

    normal_file = OUTPUT_DIR / "normal.json"

    with open(normal_file, "r", encoding="utf-8") as f:
        scenario = json.load(f)

    scenario["scenarioId"] = "talk"
    scenario["title"] = "동승자 대화 중 정상 주행"

    # 운전자 외 앞좌석 동승자가 탑승한 상황
    for sample in scenario["samples"]:
        sample["seats"]["frontPassenger"] = True

    output_file = OUTPUT_DIR / "talk.json"

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(
            scenario,
            f,
            ensure_ascii=False,
            separators=(",", ":")
        )

    print("동승자 대화 시나리오 생성 완료!")
    print("저장 위치:", output_file)
    print("샘플 개수:", len(scenario["samples"]))


def generate_assist():
    """
    주행 보조 시나리오.

    0~15분: 정상 주행
    15~20분: 크루즈 작동
    20분: 크루즈 해제
    해제 10초 후: 급조향

    실제 HANDOVER_UNSAFE 판정은 엔진 검증 필요.
    """

    samples = []

    duration_sec = 1800
    interval_ms = 100
    speed = 98.0

    cruise_start = 900.0
    cruise_end = 1200.0
    rapid_start = 1210.0

    for t_ms in range(0, duration_sec * 1000, interval_ms):

        t_sec = t_ms / 1000.0

        # 기본 정상 주행 조향
        steering = math.sin(
            2 * math.pi * 0.4 * t_sec
        )

        # 크루즈 작동 중에는 조향 움직임을 작게 설정
        if cruise_start <= t_sec < cruise_end:
            steering = 0.1 * math.sin(
                2 * math.pi * 0.2 * t_sec
            )

        # 크루즈 해제 후 10초에 급조향
        if rapid_start <= t_sec < rapid_start + 0.2:
            steering += 10.0

        cruise_active = cruise_start <= t_sec < cruise_end

        distance = max(
            1000.0,
            80000.0 - speed / 3.6 * t_sec
        )

        sample = {
            "tMs": t_ms,
            "steeringDeg": round(steering, 3),
            "speedKph": speed,
            "gear": "D",
            "turnSignal": "OFF",
            "brake": False,

            "seats": {
                "driver": True,
                "frontPassenger": False,
                "rearLeft": False,
                "rearCenter": False,
                "rearRight": False
            },

            "cruise": {
                "active": cruise_active,
                "targetKph": speed if cruise_active else None
            },

            "distToNextTurnM": round(distance, 1),
            "cabinTempC": 23.5
        }

        samples.append(sample)

    scenario = {
        "formatVersion": 1,
        "scenarioId": "assist",
        "title": "주행 보조 해제 후 급조향",
        "startAt": "2026-11-07T14:00:00+09:00",

        "speedSegments": [
            {
                "fromSec": 0,
                "toSec": 900,
                "factor": 10
            },
            {
                "fromSec": 900,
                "toSec": 1800,
                "factor": 1
            }
        ],

        "samples": samples
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    output_file = OUTPUT_DIR / "assist.json"

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(
            scenario,
            f,
            ensure_ascii=False,
            separators=(",", ":")
        )

    print("주행 보조 시나리오 생성 완료!")
    print("저장 위치:", output_file)
    print("샘플 개수:", len(samples))


def generate_departure():
    """
    출발 피로 데모 시나리오.

    출발 직후부터 조향 패턴이 정상 주행과 다르다.
    출발 피로 판정 예상 시점: 약 15분 10초.

    실제 엔진 판정은 별도 검증 필요.
    """

    samples = []

    duration_sec = 1800
    interval_ms = 100
    speed = 98.0

    for t_ms in range(0, duration_sec * 1000, interval_ms):

        t_sec = t_ms / 1000.0

        # 정상보다 조향 움직임이 적은 기본 패턴
        steering = 0.5 * math.sin(
            2 * math.pi * 0.2 * t_sec
        )

        # 6초마다 큰 조향 이벤트
        # 분당 약 10회
        large_event = t_sec % 6.0

        if large_event < 0.2:
            steering += 7.0

        distance = max(
            1000.0,
            80000.0 - speed / 3.6 * t_sec
        )

        sample = {
            "tMs": t_ms,
            "steeringDeg": round(steering, 3),
            "speedKph": speed,
            "gear": "D",
            "turnSignal": "OFF",
            "brake": False,

            "seats": {
                "driver": True,
                "frontPassenger": False,
                "rearLeft": False,
                "rearCenter": False,
                "rearRight": False
            },

            "cruise": {
                "active": False,
                "targetKph": None
            },

            "distToNextTurnM": round(distance, 1),
            "cabinTempC": 23.5
        }

        samples.append(sample)

    scenario = {
        "formatVersion": 1,
        "scenarioId": "departure",
        "title": "출발 직후 피로 징후",
        "startAt": "2026-11-07T22:55:00+09:00",

        "speedSegments": [
            {
                "fromSec": 0,
                "toSec": 900,
                "factor": 10
            },
            {
                "fromSec": 900,
                "toSec": 1800,
                "factor": 1
            }
        ],

        "samples": samples
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    output_file = OUTPUT_DIR / "departure.json"

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(
            scenario,
            f,
            ensure_ascii=False,
            separators=(",", ":")
        )

    print("출발 피로 시나리오 생성 완료!")
    print("저장 위치:", output_file)
    print("샘플 개수:", len(samples))


if __name__ == "__main__":
    generate_normal()
    generate_drowsy()
    generate_distraction()
    generate_talk()
    generate_assist()
    generate_departure()
