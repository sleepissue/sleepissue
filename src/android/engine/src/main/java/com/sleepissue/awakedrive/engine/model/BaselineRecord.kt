package com.sleepissue.awakedrive.engine.model

/**
 * 운행이 끝날 때 BaselineStore가 남기는 한 운행의 기준선 기록. (SRS Data Interface MSG-13)
 *
 * '평소의 나'(최근 10회 중앙값)와 개인 졸음 시점을 계산하는 재료다.
 * 엔진은 Android를 모르므로 Room 어노테이션은 붙이지 않는다.
 * Room 저장용 Entity는 app/data/에서 이 클래스와 서로 바꿔 쓰는 형태로 만든다.
 */
data class BaselineRecord(
    /** 운행 번호 (TripStateStore의 tripId) */
    val tripId: Long,
    /** 깨어 있던 구간의 미세 수정 분당 횟수 */
    val microPerMin: Float,
    /** 깨어 있던 구간의 큰 수정 분당 횟수 */
    val largePerMin: Float,
    /** 보조 주행 해제 직후 30초의 큰 수정 횟수. 인계 품질 판단의 '평소 해제 때 값'. 기록 없으면 null (REQ_AST_007) */
    val handoverLargeCount: Int? = null,
    /** 이 운행 첫 음성 확인의 대답 시간(ms). 없으면 null (REQ_CHK_004) */
    val firstVoiceLatencyMs: Int? = null,
    /** 운전 시작 뒤 처음 2단계가 된 시점(분). 없으면 null. 개인 졸음 시점 계산용 (REQ_FCS_002) */
    val firstStage2Min: Int? = null,
    /** '깨어 있던 구간' 조건을 모두 만족했는지. false면 평소의 나 계산에서 뺀다 (REQ_BSL_002) */
    val validAwake: Boolean = false,
)
