package com.sleepissue.awakedrive.engine.voice

import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechIntentParserTest {
    private val parser = SpeechIntentParser()

    @Test
    fun `운전자 확인에서는 괜찮아와 쉴게만 허용한다`() {
        assertEquals(SpeechIntent.OK, parser.parse("네", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.OK, parser.parse("괜찮아", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.REST, parser.parse("쉴게", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.NONE, parser.parse("응", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.REST, parser.parse("쉬어 가요", QuestionType.DRIVER_CHECK))
    }

    @Test
    fun `STT가 핵심어 주변 문장을 붙여도 의도를 찾는다`() {
        assertEquals(SpeechIntent.OK, parser.parse("네 크게 했다", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.REST, parser.parse("네 이제 쉴게", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.NONE, parser.parse("안내할게", QuestionType.DRIVER_CHECK))
    }

    @Test
    fun `부정 표현은 포함된 긍정 표현보다 먼저 차단한다`() {
        assertEquals(SpeechIntent.NONE, parser.parse("안 쉴게", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.NONE, parser.parse("안 괜찮아", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.NONE, parser.parse("안 잘게", QuestionType.REST_ARRIVAL))
        assertEquals(SpeechIntent.NONE, parser.parse("통풍 켜 줘", QuestionType.VENTILATION_CONTROL))
        assertEquals(SpeechIntent.NONE, parser.parse("안 졸려", QuestionType.PRE_DRIVE_CONDITION))
    }

    @Test
    fun `같은 표현도 질문 종류에 맞는 의도로 바꾼다`() {
        assertEquals(SpeechIntent.OK, parser.parse("네", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.YES, parser.parse("네", QuestionType.WAYPOINT_CONFIRMATION))
        assertEquals(SpeechIntent.NO, parser.parse("괜찮아", QuestionType.WAYPOINT_CONFIRMATION))
        assertEquals(SpeechIntent.NONE, parser.parse("쉴게", QuestionType.WAYPOINT_CONFIRMATION))
    }

    @Test
    fun `SRS 인정 표현과 띄어쓰기 차이를 처리한다`() {
        assertEquals(SpeechIntent.OK, parser.parse("괜찮습니다", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.REST, parser.parse("쉬어 갈게", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.YES, parser.parse("넣어 줘", QuestionType.WAYPOINT_CONFIRMATION))
        assertEquals(SpeechIntent.NO, parser.parse("됐어", QuestionType.WAYPOINT_CONFIRMATION))
        assertEquals(SpeechIntent.COND_SLEEPY, parser.parse("피곤해요", QuestionType.PRE_DRIVE_CONDITION))
    }

    @Test
    fun `경유지 질문에서는 응과 아니만 허용한다`() {
        assertEquals(SpeechIntent.YES, parser.parse("응", QuestionType.WAYPOINT_CONFIRMATION))
        assertEquals(SpeechIntent.NO, parser.parse("아니요", QuestionType.WAYPOINT_CONFIRMATION))
        assertEquals(SpeechIntent.NONE, parser.parse("쉴게", QuestionType.WAYPOINT_CONFIRMATION))
    }

    @Test
    fun `표에 없는 문장은 행동을 만들지 않는다`() {
        assertEquals(SpeechIntent.NONE, parser.parse("아무 말이나 합니다", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.NONE, parser.parse("안내할게요", QuestionType.DRIVER_CHECK))
    }
}
