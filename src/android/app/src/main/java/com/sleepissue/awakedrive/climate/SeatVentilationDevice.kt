package com.sleepissue.awakedrive.climate

import android.content.Context
import com.sleepissue.awakedrive.engine.climate.VentilationCapability

/**
 * 운전석 통풍 장치 하나 (동승석은 다루지 않는다 — 기획서 6장).
 * 실제 구현은 `pleos/PleosSeatVentilationDevice`, 테스트는 가짜 구현.
 * 권한이 없으면 SecurityException, 그 밖의 SDK 실패는 다른 예외로 던진다.
 */
interface SeatVentilationDevice {
    /** null = 운전석 개별 영역을 지원하지 않음 */
    suspend fun capability(): VentilationCapability?
    suspend fun read(): Int?
    suspend fun write(level: Int)
}

/**
 * 앱이 통풍을 바꾸기 전 값. 휴식 인증 뒤(또는 "통풍 꺼 줘") 되돌릴 때 쓴다.
 * 쉼터에서 시동을 끄는 경우가 많으므로 프로세스가 죽어도 남도록 저장한다.
 */
interface VentilationMemory {
    var priorLevel: Int?
}

class InMemoryVentilationMemory(override var priorLevel: Int? = null) : VentilationMemory

/** SharedPreferences(앱 전용 작은 키-값 저장소)에 한 값만 둔다. 기록이 아니라 되돌릴 값 하나뿐이라 Room을 쓰지 않는다. */
class PrefsVentilationMemory(context: Context) : VentilationMemory {
    private val prefs = context.applicationContext.getSharedPreferences("climate", Context.MODE_PRIVATE)

    override var priorLevel: Int?
        get() = if (prefs.contains(KEY)) prefs.getInt(KEY, 0) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putInt(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "driver_ventilation_prior_level"
    }
}
