package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage

/**
 * [4번] [RestCertifier] 상태를 재부팅 너머로 옮기는 값. 앱은 [encode] 문자열만 저장한다.
 *
 * 형식은 `키=값` 줄 목록이다. 외부 라이브러리 없이 순수 Kotlin에서 읽고 쓰기 위해서다.
 * 모르는 버전·깨진 값이면 [decode]가 null을 돌려주고, 앱은 새 상태로 시작한다(휴식 하나를 놓치는 쪽이 낫다).
 */
data class RestCertifierSnapshot(
    val sessionId: String? = null,
    val areaId: String? = null,
    val startedAtMs: Long? = null,
    val driveSecBeforeRest: Long? = null,
    val maxStageBeforeRest: InterventionStage? = null,
    val certified: Boolean = false,
    val lastLocation: GeoPoint? = null,
    val lastObservedMs: Long? = null,
    val maxStageSinceRest: InterventionStage = InterventionStage.NORMAL,
) {
    fun encode(): String = listOf(
        "v" to VERSION.toString(),
        "sessionId" to sessionId,
        "areaId" to areaId,
        "startedAtMs" to startedAtMs?.toString(),
        "driveSecBeforeRest" to driveSecBeforeRest?.toString(),
        "maxStageBeforeRest" to maxStageBeforeRest?.name,
        "certified" to certified.toString(),
        "lastLat" to lastLocation?.lat?.toString(),
        "lastLon" to lastLocation?.lon?.toString(),
        "lastObservedMs" to lastObservedMs?.toString(),
        "maxStageSinceRest" to maxStageSinceRest.name,
    ).filter { it.second != null }.joinToString("\n") { (k, v) -> "$k=${v!!.replace("\n", " ")}" }

    companion object {
        private const val VERSION = 1

        fun decode(text: String?): RestCertifierSnapshot? = try {
            val map = text.orEmpty().lineSequence().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            if (map["v"]?.toInt() != VERSION) null else {
                val lat = map["lastLat"]?.toDouble()
                val lon = map["lastLon"]?.toDouble()
                RestCertifierSnapshot(
                    sessionId = map["sessionId"],
                    areaId = map["areaId"],
                    startedAtMs = map["startedAtMs"]?.toLong(),
                    driveSecBeforeRest = map["driveSecBeforeRest"]?.toLong(),
                    maxStageBeforeRest = map["maxStageBeforeRest"]?.let(InterventionStage::valueOf),
                    certified = map["certified"]?.toBooleanStrict() ?: false,
                    lastLocation = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                    lastObservedMs = map["lastObservedMs"]?.toLong(),
                    maxStageSinceRest = map["maxStageSinceRest"]?.let(InterventionStage::valueOf) ?: InterventionStage.NORMAL,
                )
            }
        } catch (e: IllegalArgumentException) {
            null // NumberFormatException 포함
        }
    }
}
