package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.time.DemoClock

/** 실제 음성 시작 콜백에서 즉시 1배속. 이전 질문의 늦은 종료는 새 질문을 풀지 않는다. */
class DemoVoiceSpeedGate(private val clock: DemoClock, private val changed: (RequestId, ExecutionStatus) -> Unit) {
    private var active: RequestId? = null
    @Synchronized fun onLifecycle(id: RequestId, status: ExecutionStatus) {
        if (status == ExecutionStatus.STARTED) {
            if (active != null && active != id) return
            active = id
            clock.setQuestionActive(true)
            changed(id, status)
        } else if (active == id) {
            active = null
            clock.setQuestionActive(false)
            changed(id, status)
        }
    }
    @Synchronized fun cancel() { active?.let { onLifecycle(it, ExecutionStatus.CANCELLED) } }
}
