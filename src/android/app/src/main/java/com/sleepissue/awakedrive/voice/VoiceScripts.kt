package com.sleepissue.awakedrive.voice

object VoiceScripts {
    fun forecast(timeText: String): String =
        "오늘은 ${timeText}쯤 졸릴 수 있어요. 그때쯤 가까운 졸음쉼터를 알려 드릴게요."

    fun stage1(restAreaName: String, distanceKm: Int): String =
        "${distanceKm}킬로미터 앞에 ${restAreaName}이 있어요."

    const val DRIVER_CHECK = "잠깐 확인할게요. 신호음이 나면 '네'라고 말해 주세요."
    const val DRIVER_CHECK_OK = "좋아요."
    const val VENTILATION_ON = "운전석 통풍을 켰어요. 쉼터에서 쉬는 게 가장 좋아요."
    const val PASSENGER_CHECK =
        "동승자분께 여쭤볼게요. 운전자분이 졸려 보이면 '쉬어 가요'라고 말해 주세요."

    fun stage3(restAreaName: String, distanceKm: Int): String =
        "졸음 신호가 이어지고 있어요. ${distanceKm}킬로미터 앞 ${restAreaName}을 경유지로 넣을까요?"

    const val SAFETY_BYPASS = "운전대가 여러 번 크게 꺾였어요. 안전을 위해 쉬어 가요."
    const val PASSENGER_REST = "동승자분, 다음 쉼터에서 함께 쉬어 가 주세요."

    fun restArrival(restAreaName: String): String =
        "${restAreaName}에 도착했어요. 15분 쉬면 휴식이 인증돼요. " +
            "잠깐 자고 싶으면 '잠깐 잘게'라고 말해 주세요."

    const val NAP_START = "15분 뒤에 깨워 드릴게요. 시동을 끄면 알람이 울리지 않을 수 있어요."
    const val RECOVERY_CHECK = "출발 전에 확인할게요. 신호음이 나면 '네'라고 말해 주세요."
    const val RECOVERY_OK = "다시 출발해도 좋아요."
    const val RECOVERY_SLOW = "10분 더 쉬어 가는 건 어때요?"

    // 4번 추가 (기획서 6장 통풍 규칙·7-1 파워냅)
    const val VENTILATION_MANUAL = "통풍을 켜 보세요. 쉼터에서 쉬는 게 가장 좋아요."
}
