package com.sleepissue.awakedrive.session

import android.media.AudioManager
import android.media.ToneGenerator
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.rest.NapEvent
import com.sleepissue.awakedrive.engine.rest.NapStart
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.engine.rest.NapTimer
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.SignalHubSession
import com.sleepissue.awakedrive.voice.VoiceScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/** 점점 커지는 알람. 실제 구현은 [ToneNapAlarm], 테스트는 가짜. */
interface NapAlarm {
    fun start(rampMs: Long, maxMs: Long)
    fun stop()
}

/**
 * [4번] NapSession — 정차 중 파워냅 (기획서 7-1, SRS RES 파워냅).
 *
 * 운전자가 "잠깐 잘게"(3번 SpeechIntent NAP) 또는 [잠깐 잘게] 버튼일 때만 [request]를 부른다.
 * 15분 뒤 알람 → 운전자가 끄면 [events]에 Ended(recoveryCheck = true) → 조립 지점에서 1번/3번 회복 확인으로 잇는다.
 * 앱은 실제로 잤는지 판단하지 않는다. 쉼터에서 P단이면 휴식 인증은 [RestSessionMonitor]가 따로 한다.
 *
 * @param speak 3번 VoiceAgent로 연결. 끝날 때까지 기다리는 suspend 함수
 */
class NapSession(
    private val timer: NapTimer,
    private val alarm: NapAlarm,
    private val speak: suspend (String) -> Unit,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow<NapState>(NapState.Idle)
    val state: StateFlow<NapState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<NapEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NapEvent> = mutableEvents.asSharedFlow()

    /** 파워냅 화면의 남은 시간. */
    fun remainingMs(): Long? = timer.remainingMs(clock.scenarioTimeMs())

    suspend fun request(gear: Gear?, atRestArea: Boolean): NapStart {
        val started = lock.withLock {
            timer.start(clock.scenarioTimeMs(), gear, atRestArea).also { mutableState.value = timer.state }
        }
        if (started is NapStart.Started) {
            if (started.needsSafetyNotice) speak(VoiceScripts.NAP_SAFETY_CHECK)
            speak(VoiceScripts.NAP_START) // "시동을 끄면 알람이 울리지 않을 수 있어요"
        }
        return started
    }

    /** [지금 깨우기] 또는 알람 끄기. */
    suspend fun wake() = lock.withLock { timer.wake()?.let { finish(it) } }

    suspend fun tick(gear: Gear?) = lock.withLock {
        when (val event = timer.tick(clock.scenarioTimeMs(), gear)) {
            NapEvent.AlarmDue -> {
                alarm.start(config.napAlarmRampMs, config.napAlarmMaxMs)
                mutableState.value = timer.state
                mutableEvents.emit(event)
            }
            is NapEvent.Ended -> finish(event)
            null -> Unit
        }
    }

    private suspend fun finish(event: NapEvent) {
        alarm.stop()
        mutableState.value = timer.state
        mutableEvents.emit(event)
    }

    /** Hub의 기어를 1초마다 읽어 타이머를 진행한다. */
    fun attach(scope: CoroutineScope, hub: SignalHubSession): Job = scope.launch {
        while (isActive) {
            tick(hub.latestFrame.value?.gear)
            delay(config.naviStateIntervalMs)
        }
    }
}

/**
 * ToneGenerator로 1초마다 삑 소리를 내며 [rampMs] 동안 소리를 키운다.
 * 소리 크기·간격은 판단 문턱이 아니라 소리 연출이라 여기 둔다. 시동(전원)이 꺼지면 울리지 않는다(시작할 때 안내함).
 */
class ToneNapAlarm(private val scope: CoroutineScope) : NapAlarm {
    private var job: Job? = null

    override fun start(rampMs: Long, maxMs: Long) {
        stop()
        job = scope.launch {
            var elapsed = 0L
            while (isActive && elapsed < maxMs) {
                val ratio = min(1.0, elapsed.toDouble() / rampMs)
                val volume = (MIN_VOLUME + (ToneGenerator.MAX_VOLUME - MIN_VOLUME) * ratio).toInt()
                val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volume) }.getOrNull()
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_MS)
                delay(PERIOD_MS.toLong())
                tone?.release()
                elapsed += PERIOD_MS
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val MIN_VOLUME = 30 // 0~100
        const val BEEP_MS = 600
        const val PERIOD_MS = 1_000
    }
}
