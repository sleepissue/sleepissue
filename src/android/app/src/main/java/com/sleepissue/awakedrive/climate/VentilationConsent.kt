package com.sleepissue.awakedrive.climate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [4번] 운전석 통풍 사전 동의 (기획서 6장 통풍 규칙, 9장 "통풍은 사전 동의한 경우만").
 *
 * 첫 실행 설정 화면에서 받는다. 기본값은 false(동의 없음)다 — 묻지 않고 켜지 않는다.
 * 감시 파이프라인이 [consent]를 구독해 1번 매니저에 `VentilationConsentChanged`로 넘긴다.
 * (StateFlow: 늘 최신 값 하나를 들고 있는 토픽. 새 구독자는 지금 값을 바로 받는다.)
 */
interface VentilationConsentStore {
    val consent: StateFlow<Boolean>
    fun set(enabled: Boolean)
}

class InMemoryVentilationConsentStore(initial: Boolean = false) : VentilationConsentStore {
    private val state = MutableStateFlow(initial)
    override val consent: StateFlow<Boolean> = state.asStateFlow()
    override fun set(enabled: Boolean) { state.value = enabled }
}

/**
 * 앱 전체에서 하나만 쓴다([get]). 화면(설정)과 감시 서비스가 같은 프로세스에서 같은 StateFlow를 보게 하기 위해서다.
 * 값은 SharedPreferences에 남아 재부팅 뒤에도 유지된다.
 */
class PrefsVentilationConsentStore private constructor(context: Context) : VentilationConsentStore {
    private val prefs = context.applicationContext.getSharedPreferences("climate", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, false))
    override val consent: StateFlow<Boolean> = state.asStateFlow()

    override fun set(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
    }

    companion object {
        private const val KEY = "driver_ventilation_consent"
        @Volatile private var instance: PrefsVentilationConsentStore? = null

        fun get(context: Context): PrefsVentilationConsentStore =
            instance ?: synchronized(this) { instance ?: PrefsVentilationConsentStore(context).also { instance = it } }
    }
}
