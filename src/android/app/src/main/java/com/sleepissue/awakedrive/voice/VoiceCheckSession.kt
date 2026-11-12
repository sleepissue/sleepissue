package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckResult
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.QuestionType

/** 음성 확인의 시작·취소 계약. 기기 구현과 JVM 시험이 같은 응답 경계를 쓴다. */
interface VoiceCheckSession {
    fun start(
        baselineMs: Long,
        questionType: QuestionType,
        checkType: CheckType = CheckType.VOICE,
        onResult: (CheckResult, Boolean) -> Unit,
        onSttFallbackRequired: () -> Unit,
    )
    fun cancel()
}
