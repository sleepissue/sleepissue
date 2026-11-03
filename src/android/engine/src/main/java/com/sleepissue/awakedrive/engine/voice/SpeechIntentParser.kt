package com.sleepissue.awakedrive.engine.voice

import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent

class SpeechIntentParser {
    private val phrases = mapOf(
        "네" to SpeechIntent.OK,
        "괜찮아" to SpeechIntent.OK,
        "괜찮아요" to SpeechIntent.OK,
        "쉴게" to SpeechIntent.REST,
        "쉬어갈게" to SpeechIntent.REST,
        "쉬어가요" to SpeechIntent.REST,
        "잠깐잘게" to SpeechIntent.NAP,
        "응" to SpeechIntent.YES,
        "예" to SpeechIntent.YES,
        "아니" to SpeechIntent.NO,
        "아니요" to SpeechIntent.NO,
        "통풍꺼줘" to SpeechIntent.VENT_OFF,
        "개운해요" to SpeechIntent.COND_FRESH,
        "보통" to SpeechIntent.COND_NORMAL,
        "졸려요" to SpeechIntent.COND_SLEEPY,
    )

    private val allowed = mapOf(
        QuestionType.DRIVER_CHECK to setOf(SpeechIntent.OK, SpeechIntent.REST),
        QuestionType.PASSENGER_CHECK to setOf(SpeechIntent.OK, SpeechIntent.REST),
        QuestionType.WAYPOINT_CONFIRMATION to setOf(SpeechIntent.YES, SpeechIntent.NO),
        QuestionType.REST_ARRIVAL to setOf(SpeechIntent.NAP),
        QuestionType.PRE_DRIVE_CONDITION to setOf(
            SpeechIntent.COND_FRESH,
            SpeechIntent.COND_NORMAL,
            SpeechIntent.COND_SLEEPY,
        ),
        QuestionType.VENTILATION_CONTROL to setOf(SpeechIntent.VENT_OFF),
    )

    fun parse(rawText: String, questionType: QuestionType): SpeechIntent {
        val normalized = rawText.trim().replace(" ", "")
        val intent = phrases[normalized] ?: return SpeechIntent.NONE
        return intent.takeIf { it in allowed.getValue(questionType) } ?: SpeechIntent.NONE
    }
}
