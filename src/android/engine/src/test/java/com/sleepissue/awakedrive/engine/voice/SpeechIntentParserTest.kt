package com.sleepissue.awakedrive.engine.voice

import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechIntentParserTest {
    private val parser = SpeechIntentParser()

    @Test
    fun `운전자 확인에서는 괜찮아와 쉴게만 허용한다`() {
        assertEquals(SpeechIntent.OK, parser.parse("괜찮아", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.REST, parser.parse("쉴게", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.NONE, parser.parse("응", QuestionType.DRIVER_CHECK))
        assertEquals(SpeechIntent.REST, parser.parse("쉬어 가요", QuestionType.DRIVER_CHECK))
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
    }
}
