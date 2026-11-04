package com.sleepissue.awakedrive.engine.rest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAreaCsvTest {
    private val header = RestAreaCsv.COLUMNS.joinToString(",")

    @Test
    fun `한 줄을 모델로 바꾼다`() {
        val text = "$header\nDS-1-검단-부산,DROWSY_SHELTER,검단졸음쉼터,경부선,1,서울기점 + 부산종점,부산,N,142.1,35.91,128.61,Y\n"
        val area = RestAreaCsv.parse(text).single()
        assertEquals("DS-1-검단-부산", area.id)
        assertEquals(RestAreaKind.DROWSY_SHELTER, area.kind)
        assertEquals("부산", area.towardCity)
        assertEquals(142.1f, area.carriagewayBearingDeg!!, 1e-4f)
        assertEquals(35.91, area.location.lat, 1e-9)
        assertTrue(area.hasToilet)
    }

    @Test
    fun `빈 방위각은 방향 모름, 양방향은 Y로 읽는다`() {
        val text = "$header\nSA-1-평택-BOTH,SERVICE_AREA,평택휴게소,평택화성선,40,양방향,,Y,,37.0,127.0,Y\n"
        val area = RestAreaCsv.parse(text).single()
        assertTrue(area.bothDirections)
        assertNull(area.carriagewayBearingDeg)
        assertNull(area.towardCity)
    }

    @Test
    fun `따옴표 필드와 BOM·CRLF를 처리한다`() {
        val text = "﻿$header\r\n" +
            "x,SERVICE_AREA,\"쉼표, 있는 \"\"이름\"\"\",경부선,1,상행,서울,N,10.0,37.0,127.0,N\r\n"
        assertEquals("쉼표, 있는 \"이름\"", RestAreaCsv.parse(text).single().name)
    }

    @Test
    fun `필수 필드가 비면 몇째 줄인지 알려 준다`() {
        val text = "$header\nx,DROWSY_SHELTER,,경부선,1,상행,서울,N,10.0,37.0,127.0,Y\n"
        val e = assertThrows(IllegalArgumentException::class.java) { RestAreaCsv.parse(text) }
        assertTrue(e.message!!, e.message!!.contains("2번째 줄") && e.message!!.contains("name"))
    }

    @Test
    fun `머리글 열이 빠지면 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { RestAreaCsv.parse("id,name\nx,y\n") }
    }
}
