package com.sleepissue.awakedrive.session

import android.content.Context
import com.sleepissue.awakedrive.engine.rest.RestCertifierSnapshot

/**
 * [4번] 휴식 인증 진행 상태를 재부팅 너머로 남긴다 (기획서 7-3 "시동을 껐다 켜도 끊김 없음").
 *
 * 기록(스탬프)은 Room([RewardLedger])에 있고, 여기는 "지금 쉼터에서 몇 분째 쉬는 중인가" 같은 진행 상태 하나뿐이라
 * SharedPreferences에 문자열 하나로 둔다.
 */
interface RestStateStore {
    fun load(): RestCertifierSnapshot?
    fun save(snapshot: RestCertifierSnapshot)
}

class InMemoryRestStateStore(var saved: RestCertifierSnapshot? = null) : RestStateStore {
    var saves = 0
        private set
    override fun load() = saved
    override fun save(snapshot: RestCertifierSnapshot) { saved = snapshot; saves++ }
}

class PrefsRestStateStore(context: Context) : RestStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("rest_session", Context.MODE_PRIVATE)

    override fun load(): RestCertifierSnapshot? = RestCertifierSnapshot.decode(prefs.getString(KEY, null))

    // apply(): 디스크 쓰기를 백그라운드로 미룬다. 1초마다 불려도 바뀐 경우에만 저장한다(RestSessionMonitor).
    override fun save(snapshot: RestCertifierSnapshot) {
        prefs.edit().putString(KEY, snapshot.encode()).apply()
    }

    fun flush() { check(prefs.edit().putString(KEY, prefs.getString(KEY, null)).commit()) }

    private companion object {
        const val KEY = "certifier_v1"
    }
}
