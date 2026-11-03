package com.sleepissue.awakedrive.engine.voice

import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent

/** SRS REQ_VOI_008의 질문별 인정·제외 표현을 적용한다. */
class SpeechIntentParser {
    private data class Rule(
        val intent: SpeechIntent,
        val accepted: List<String>,
        val excluded: List<String> = emptyList(),
    )

    private val rules = mapOf(
        QuestionType.DRIVER_CHECK to listOf(
            Rule(SpeechIntent.REST, listOf("쉴게", "쉬어갈게", "쉬자", "쉬어가요"), listOf("안쉴게", "쉬지말자", "안쉬어")),
            Rule(SpeechIntent.OK, listOf("네", "괜찮아", "괜찮아요", "괜찮습니다", "멀쩡해"), listOf("안괜찮아")),
        ),
        QuestionType.PASSENGER_CHECK to listOf(
            Rule(SpeechIntent.REST, listOf("쉴게", "쉬어갈게", "쉬자", "쉬어가요"), listOf("안쉴게", "쉬지말자", "안쉬어")),
            Rule(SpeechIntent.OK, listOf("괜찮아", "괜찮아요", "괜찮습니다", "멀쩡해"), listOf("안괜찮아")),
        ),
        QuestionType.WAYPOINT_CONFIRMATION to listOf(
            Rule(SpeechIntent.NO, listOf("아니", "아니요", "싫어", "됐어", "괜찮아")),
            Rule(SpeechIntent.YES, listOf("응", "네", "예", "좋아", "넣어줘", "그래")),
        ),
        QuestionType.REST_ARRIVAL to listOf(
            Rule(SpeechIntent.NAP, listOf("잠깐잘게", "좀잘게", "잘게"), listOf("안잘게")),
        ),
        QuestionType.PRE_DRIVE_CONDITION to listOf(
            Rule(SpeechIntent.COND_SLEEPY, listOf("졸려요", "졸려", "피곤해요"), listOf("안졸려")),
            Rule(SpeechIntent.COND_FRESH, listOf("개운해요", "개운해", "좋아요")),
            Rule(SpeechIntent.COND_NORMAL, listOf("보통", "그냥그래요", "보통이에요")),
        ),
        QuestionType.VENTILATION_CONTROL to listOf(
            Rule(SpeechIntent.VENT_OFF, listOf("통풍꺼줘", "통풍꺼", "바람꺼줘"), listOf("통풍켜줘", "켜줘")),
        ),
    )

    fun parse(rawText: String, questionType: QuestionType): SpeechIntent {
        val normalized = rawText.filter(Char::isLetterOrDigit)
        if (normalized.isEmpty()) return SpeechIntent.NONE
        val tokens = rawText.split(Regex("[^가-힣A-Za-z0-9]+"))
            .filter(String::isNotBlank)
        val questionRules = rules.getValue(questionType)
        if (questionRules.any { rule -> rule.excluded.any(normalized::contains) }) return SpeechIntent.NONE
        return questionRules.firstNotNullOfOrNull { rule ->
            rule.intent.takeIf {
                rule.accepted.any { phrase ->
                    if (phrase.length == 1) phrase in tokens else normalized.contains(phrase)
                }
            }
        } ?: SpeechIntent.NONE
    }
}
