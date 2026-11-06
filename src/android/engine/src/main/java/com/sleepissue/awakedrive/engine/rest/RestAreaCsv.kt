package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint

// [4번] 내장 쉼터 표(data/rest_areas/processed/rest_areas.csv) 읽기 — REQ_RST_001.
// 파일을 여는 건 app(RestAreaRepository, assets)이고, 여기서는 글자만 받아 모델로 바꾼다.
// 그래서 에뮬레이터 없이 :engine 테스트로 표 전체를 검사할 수 있다.
//
// 형식: UTF-8, 쉼표 구분, 첫 줄 머리글. 따옴표 필드("a,b"·"" 이스케이프)를 지원한다.
// 필수 필드(이름·종류·위도·경도·노선·방향)가 비었거나 형식이 틀리면 몇째 줄인지 붙여 예외를 던진다.
// 앱이 엉터리 표로 조용히 돌기보다 빌드·테스트 단계에서 바로 드러나게 하려는 것이다.
object RestAreaCsv {

    val COLUMNS = listOf(
        "id", "kind", "name", "route_name", "route_no", "direction_label", "toward_city",
        "both_directions", "carriageway_bearing_deg", "lat", "lon", "toilet",
    )

    fun parse(text: String): List<RestArea> {
        val rows = splitRows(text.removePrefix("﻿"))
        require(rows.isNotEmpty()) { "쉼터 표가 비어 있습니다." }
        val header = rows.first()
        val missing = COLUMNS - header.toSet()
        require(missing.isEmpty()) { "쉼터 표 머리글에 없는 열: $missing" }
        val index = header.withIndex().associate { (i, name) -> name to i }

        return rows.drop(1).mapIndexed { i, cells ->
            val line = i + 2 // 머리글이 1번째 줄
            require(cells.size == header.size) { "${line}번째 줄: 열 수 ${cells.size} ≠ 머리글 ${header.size}" }
            fun cell(name: String) = cells[index.getValue(name)].trim()
            fun required(name: String) = cell(name).also { require(it.isNotEmpty()) { "${line}번째 줄: '$name' 결측" } }
            fun double(name: String) = required(name).toDoubleOrNull() ?: error("${line}번째 줄: '$name' 숫자 아님")
            try {
                RestArea(
                    id = required("id"),
                    name = required("name"),
                    kind = RestAreaKind.valueOf(required("kind")),
                    location = GeoPoint(double("lat"), double("lon")),
                    routeName = required("route_name"),
                    directionLabel = required("direction_label"),
                    towardCity = cell("toward_city").ifEmpty { null },
                    bothDirections = cell("both_directions") == "Y",
                    carriagewayBearingDeg = cell("carriageway_bearing_deg").ifEmpty { null }
                        ?.let { it.toFloatOrNull() ?: error("${line}번째 줄: 'carriageway_bearing_deg' 숫자 아님") },
                    hasToilet = cell("toilet") == "Y",
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("${line}번째 줄: ${e.message}", e)
            }
        }
    }

    // RFC 4180 수준의 단순 분리. 빈 줄은 건너뛴다.
    private fun splitRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.clear() }
        fun endRow() {
            endCell()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endCell()
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> cell.append(c)
            }
            i++
        }
        require(!quoted) { "쉼터 표의 따옴표가 닫히지 않았습니다." }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
