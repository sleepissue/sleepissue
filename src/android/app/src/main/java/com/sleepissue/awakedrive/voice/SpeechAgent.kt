package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser

/** SDK 콜백의 원문을 즉시 enum으로 바꾸며 원문은 저장하지 않는다. */
class SpeechAgent(private val parser: SpeechIntentParser = SpeechIntentParser()) {
    fun onRecognized(rawText: String, questionType: QuestionType): SpeechIntent =
        parser.parse(rawText, questionType)
}
