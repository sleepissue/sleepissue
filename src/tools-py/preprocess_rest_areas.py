#!/usr/bin/env python3
"""졸음쉼터 + 휴게소 원본 CSV → 앱 내장용 표 하나 (REQ_RST_001).

입력 (data/rest_areas/, 공공데이터포털 원본, CP949):
  - 전국졸음쉼터표준데이터.csv   (15028203, 253곳)
  - 전국휴게소정보표준데이터.csv (215곳)
출력 (UTF-8):
  - data/rest_areas/processed/rest_areas.csv  ← 앱이 assets로 그대로 읽는다

실행:  python3 src/tools-py/preprocess_rest_areas.py        (저장소 루트에서)
검증만: python3 src/tools-py/preprocess_rest_areas.py --check

방향 처리 (REQ_RST_003):
  두 원본 모두 방향을 "어느 도시 쪽으로 가는 차로인가"로 적는다.
    졸음쉼터 도로노선방향 = "서울기점 + 부산종점"  → 부산 쪽 차로
    휴게소 이름 괄호      = "횡성(인천)"            → 인천 쪽 차로
  쉼터 위치에서 그 도시를 바라본 방위각을 carriageway_bearing_deg로 미리 계산해 둔다.
  엔진(RestAreaSelector)은 차 진행 방향과 이 값의 차가 허용치 안이면 같은 방향 차로로 본다.
  계산할 수 없으면(양방향·순환선·도시 너무 가까움·도시 모름) 빈칸 → 엔진에서 '방향 모름'.
"""
from __future__ import annotations

import csv
import io
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT / "data" / "rest_areas"
OUT = SRC_DIR / "processed" / "rest_areas.csv"
SHELTER_CSV = SRC_DIR / "전국졸음쉼터표준데이터.csv"
SERVICE_CSV = SRC_DIR / "전국휴게소정보표준데이터.csv"

EARTH_RADIUS_M = 6_371_008.8

# 원본 오타 → 바른 이름 [해석] 원본 그대로는 도시표에서 못 찾는다.
CITY_TYPO = {"양앙": "양양", "회턱": "회덕", "광주광역시": "광주"}

# 방향 기준 도시 좌표 [가정] 시청·군청(또는 JC·지명) 소재지 대략 좌표, 소수 둘째 자리(≈1 km).
# 방위각만 쓰므로 수 km 오차는 결과에 거의 영향이 없다. validate_cities()가 원본 시군구 주소와 대조한다.
CITY = {
    "강릉": (37.75, 128.88), "고창": (35.44, 126.70), "공주": (36.45, 127.12),
    "광주": (35.16, 126.85),  # 광주광역시. 경기 광주로 가는 노선은 원본에 '하남'으로 표기됨 [가정]
    "구리": (37.59, 127.13), "기장": (35.24, 129.22), "논산": (36.19, 127.10),
    "달서": (35.83, 128.53), "담양": (35.32, 126.99), "당진": (36.89, 126.63),
    "대구": (35.87, 128.60), "대전": (36.35, 127.38), "동대구": (35.88, 128.63),
    "목포": (34.81, 126.39), "부산": (35.18, 129.08), "산내": (36.28, 127.47),
    "산인": (35.29, 128.43), "삼척": (37.45, 129.17), "새만금": (35.82, 126.65),
    "서울": (37.57, 126.98), "서천": (36.08, 126.69), "세종": (36.48, 127.29),
    "속초": (38.21, 128.59), "순천": (34.95, 127.49), "양양": (38.08, 128.62),
    "양주": (37.79, 127.05), "양평": (37.49, 127.49), "언양": (35.56, 129.13),
    "영덕": (36.42, 129.37), "영암": (34.80, 126.70), "울산": (35.54, 129.31),
    "익산": (35.95, 126.96), "인천": (37.46, 126.71), "일산": (37.66, 126.77),
    "장수": (35.65, 127.52), "제천": (37.13, 128.19), "창원": (35.23, 128.68),
    "천안": (36.81, 127.11), "청주": (36.64, 127.49), "춘천": (37.88, 127.73),
    "통영": (34.85, 128.43), "파주": (37.76, 126.78), "판교": (37.39, 127.11),
    "평택": (36.99, 127.11), "포천": (37.89, 127.20), "포항": (36.02, 129.34),
    "하남": (37.54, 127.21), "회덕": (36.40, 127.43),
    "서대전": (36.32, 127.38), "퇴계원": (37.65, 127.14),
    # 휴게소 괄호에만 나오는 곳
    "완주": (35.90, 127.16), "남이": (36.57, 127.43), "함양": (35.52, 127.73),
    "무안": (34.99, 126.48), "서부산": (35.15, 128.99), "전주": (35.82, 127.15),
}

# 원본 시군구명으로 도시 좌표를 대조할 때 쓰는 이름 (도시표 키 → 시군구명에 들어가는 글자)
CITY_ADMIN_NAME = {"광주": "광주광역시", "일산": "고양시", "판교": "성남시", "언양": "울주군", "달서": "달서구"}

MIN_CITY_DISTANCE_M = 5_000.0  # [가설] 도시가 이보다 가까우면 방위각이 흔들려 '방향 모름'으로 둔다
RING_ROUTE = re.compile(r"순환")  # [해석] 순환선은 종점 도시 방위각이 실제 진행 방향과 크게 어긋날 수 있다


def read_cp949(path: Path) -> list[dict[str, str]]:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit(f"인코딩을 알 수 없음: {path}")
    return [{k.strip(): (v or "").strip() for k, v in row.items()} for row in csv.DictReader(io.StringIO(text))]


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def normalize_city(name: str | None) -> str | None:
    if not name:
        return None
    return CITY_TYPO.get(name, name)


def carriageway_bearing(lat: float, lon: float, route: str, toward: str | None, origin: str | None = None) -> str:
    """빈칸 = 방향 모름.

    종점(toward) 쪽 방위각과 기점(origin)에서 멀어지는 방위각 중, 기준 도시가 더 먼 쪽을 쓴다.
    도시가 가까울수록 직선 방위각이 실제 도로 방향과 어긋나기 때문이다
    (예: 평택제천선 송탄졸음쉼터는 종점 평택까지 가까워 종점 방위각이 틀어짐).
    """
    if RING_ROUTE.search(route):
        return ""
    options = []  # (기준 도시까지 거리, 방위각)
    if toward in CITY:
        clat, clon = CITY[toward]
        options.append((distance_m(lat, lon, clat, clon), bearing_deg(lat, lon, clat, clon)))
    if origin in CITY:
        olat, olon = CITY[origin]
        options.append((distance_m(lat, lon, olat, olon), (bearing_deg(lat, lon, olat, olon) + 180.0) % 360.0))
    if not options:
        return ""
    dist, bearing = max(options)
    if dist < MIN_CITY_DISTANCE_M:
        return ""
    return f"{bearing:.1f}"


def slug(*parts: str) -> str:
    return "-".join(re.sub(r"\s+", "", p) for p in parts if p)


def shelters(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    out = []
    for r in rows:
        label = r["도로노선방향"]  # "서울기점 + 부산종점"
        m = re.fullmatch(r"(\S+)기점 \+ (\S+)종점", label)
        toward = normalize_city(m.group(2)) if m else None
        origin = normalize_city(m.group(1)) if m else None
        lat, lon = float(r["위도"]), float(r["경도"])
        out.append({
            "id": slug("DS", r["도로노선번호"], r["졸음쉼터명"], toward or label),
            "kind": "DROWSY_SHELTER",
            "name": f"{r['졸음쉼터명']}졸음쉼터",
            "route_name": r["도로노선명"],
            "route_no": r["도로노선번호"],
            "direction_label": label,
            "toward_city": toward or "",
            "both_directions": "N",
            "carriageway_bearing_deg": carriageway_bearing(lat, lon, r["도로노선명"], toward, origin),
            "lat": r["위도"],
            "lon": r["경도"],
            "toilet": r["화장실유무"] or "N",
        })
    return out


def service_areas(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    def toward_of(r: dict[str, str]) -> str | None:
        m = re.search(r"\(([^)]*)\)", r["휴게소명"])
        return normalize_city(m.group(1)) if m else None

    # 같은 노선·같은 이름의 반대편 휴게소가 있으면 그쪽 방향 도시를 이쪽의 기점으로 쓴다.
    # 예: 횡성(인천)의 기점 = 강릉 (반대편 횡성(강릉)). 졸음쉼터처럼 더 먼 도시 기준으로 방위각을 고르기 위함.
    towards_by_pair: dict[tuple[str, str], set[str]] = {}
    for r in rows:
        t = toward_of(r)
        if t:
            towards_by_pair.setdefault((r["도로노선명"], re.sub(r"\(.*\)", "", r["휴게소명"])), set()).add(t)

    out = []
    for r in rows:
        toward = toward_of(r)
        others = towards_by_pair.get((r["도로노선명"], re.sub(r"\(.*\)", "", r["휴게소명"])), set()) - {toward}
        origin = next(iter(others)) if len(others) == 1 else None
        both = r["도로노선방향"] == "양방향"
        # [해석] 원본 방향 빈칸 7곳은 이름 괄호 도시로 채운다 (예: 익산미륵사지(천안) → "천안 방향").
        label = r["도로노선방향"] or (f"{toward} 방향" if toward else "")
        lat, lon = float(r["위도"]), float(r["경도"])
        base = re.sub(r"\(.*\)", "", r["휴게소명"])
        out.append({
            "id": slug("SA", r["도로노선번호"], base, "BOTH" if both else (toward or label)),
            "kind": "SERVICE_AREA",
            "name": f"{base}휴게소" if not base.endswith("휴게소") else base,
            "route_name": r["도로노선명"],
            "route_no": r["도로노선번호"],
            "direction_label": label,
            "toward_city": "" if both else (toward or ""),
            "both_directions": "Y" if both else "N",
            "carriageway_bearing_deg": "" if both else carriageway_bearing(lat, lon, r["도로노선명"], toward, origin),
            "lat": r["위도"],
            "lon": r["경도"],
            "toilet": r["화장실유무"] or "N",
        })
    return out


COLUMNS = ["id", "kind", "name", "route_name", "route_no", "direction_label", "toward_city",
           "both_directions", "carriageway_bearing_deg", "lat", "lon", "toilet"]
REQUIRED = ["id", "kind", "name", "route_name", "direction_label", "lat", "lon"]  # REQ_RST_001 필수 필드


def validate(table: list[dict[str, str]], shelter_rows: list[dict[str, str]]) -> list[str]:
    errors = []
    for col in REQUIRED:
        n = sum(1 for r in table if not r[col])
        if n:
            errors.append(f"필수 필드 '{col}' 결측 {n}건")
    ids = [r["id"] for r in table]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        errors.append(f"id 중복 {len(dup)}건: {sorted(dup)[:5]}")
    for r in table:
        lat, lon = float(r["lat"]), float(r["lon"])
        if not (33.0 <= lat <= 39.0 and 124.5 <= lon <= 132.0):
            errors.append(f"좌표가 한국 밖: {r['id']} ({lat}, {lon})")
    unknown = sorted({r["toward_city"] for r in table if r["toward_city"] and r["toward_city"] not in CITY})
    if unknown:
        errors.append(f"도시표에 없는 방향 도시: {unknown}")
    errors += validate_cities(shelter_rows)
    return errors


def validate_cities(shelter_rows: list[dict[str, str]]) -> list[str]:
    """도시표 좌표가 원본 시군구 주소의 쉼터들과 40 km 안에 있는지 대조한다 (기억에 의존한 좌표의 검산)."""
    errors = []
    for city, (clat, clon) in CITY.items():
        admin = CITY_ADMIN_NAME.get(city, city)
        near = [r for r in shelter_rows if admin in (r["시군구명"] + r["시도명"])]
        if not near:
            continue  # 그 도시 안에 졸음쉼터가 없으면 대조 불가
        dmin = min(distance_m(clat, clon, float(r["위도"]), float(r["경도"])) for r in near)
        if dmin > 40_000:
            errors.append(f"도시 좌표 의심: {city} — 같은 시군구 쉼터까지 최소 {dmin / 1000:.0f} km")
    return errors


def main() -> int:
    shelter_rows = read_cp949(SHELTER_CSV)
    table = shelters(shelter_rows) + service_areas(read_cp949(SERVICE_CSV))
    table.sort(key=lambda r: r["id"])
    errors = validate(table, shelter_rows)

    n_ds = sum(1 for r in table if r["kind"] == "DROWSY_SHELTER")
    n_sa = len(table) - n_ds
    n_unknown = sum(1 for r in table if r["both_directions"] == "N" and not r["carriageway_bearing_deg"])
    print(f"졸음쉼터 {n_ds} + 휴게소 {n_sa} = {len(table)}곳, 방향 모름 {n_unknown}곳(양방향 제외)")
    for e in errors:
        print("오류:", e)
    if errors:
        return 1
    if "--check" in sys.argv:
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n")
        w.writeheader()
        w.writerows(table)
    print("저장:", OUT.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
