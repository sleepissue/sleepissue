package com.sleepissue.awakedrive.navi

import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.rest.RestAreaKind
import com.sleepissue.awakedrive.engine.rest.RestAreaSelection
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// TC_RST_001: 앱에 들어가는 바로 그 파일(assets 원본)을 읽어 검사한다.
class RestAreaRepositoryTest {

    // :app 단위 테스트의 작업 폴더는 src/android/app
    private val table = File("../../../data/rest_areas/processed/rest_areas.csv")
    private val repository = RestAreaRepository { table.inputStream() }

    @Test
    fun `TC_RST_001 내장 CSV 행 수와 로드 수가 같고 필수 필드 결측이 0건`() {
        assertTrue("전처리본이 없습니다: ${table.absolutePath}", table.exists())
        val csvRows = table.readLines(Charsets.UTF_8).count { it.isNotBlank() } - 1 // 머리글 제외
        assertEquals(csvRows, repository.areas.size)
        // 필수 필드(이름·종류·위도·경도·노선·방향)는 RestArea 생성 시 검사되므로, 여기까지 오면 결측 0건이다.
        assertTrue(repository.areas.all { it.name.isNotBlank() && it.routeName.isNotBlank() && it.directionLabel.isNotBlank() })
    }

    @Test
    fun `졸음쉼터 253곳과 휴게소 215곳이 종류 열로 나뉘어 들어 있다`() {
        val byKind = repository.areas.groupingBy { it.kind }.eachCount()
        assertEquals(253, byKind[RestAreaKind.DROWSY_SHELTER])
        assertEquals(215, byKind[RestAreaKind.SERVICE_AREA])
        assertEquals(repository.areas.size, repository.areas.map { it.id }.toSet().size) // id 중복 없음
    }

    @Test
    fun `id로 쉼터를 찾는다`() {
        val first = repository.areas.first()
        assertEquals(first, repository.find(first.id))
        assertEquals(null, repository.find("없는-id"))
    }

    @Test
    fun `실제 표로 경부선 하행 중이면 부산 방향 쉼터만 고른다`() {
        // 남사졸음쉼터(경부선) 북쪽 약 5 km에서 남쪽으로 달리는 상황 [가정]
        val navi = NaviState(tMs = 0, lat = 37.14, lon = 127.13, headingDeg = 170f)
        val result = RestAreaSelector().select(navi, repository.areas)
        assertTrue(result is RestAreaSelection.Selected)
        val area = (result as RestAreaSelection.Selected).area
        assertNotNull(area.carriagewayBearingDeg)
        assertTrue("반대편 차로를 골랐습니다: ${area.id}", area.towardCity != "서울")
    }
}
