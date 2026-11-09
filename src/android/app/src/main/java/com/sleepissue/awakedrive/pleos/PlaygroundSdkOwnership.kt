package com.sleepissue.awakedrive.pleos

/** 같은 앱 프로세스의 실제 SDK 사용자를 하나로 제한한다. DEMO·수동 입력은 이 소유권을 사용하지 않는다. */
object PlaygroundSdkOwnership {
    class Lease internal constructor(val owner: String)

    private var current: Lease? = null

    val currentOwner: String?
        get() = synchronized(this) { current?.owner }

    /** 같은 이름의 재요청도 새 수집기를 허용하지 않는다. 시작 중복 처리는 각 소유자가 맡는다. */
    @Synchronized fun acquire(owner: String): Lease? {
        require(owner.isNotBlank()) { "SDK 소유자 이름은 비어 있을 수 없습니다." }
        if (current != null) return null
        return Lease(owner).also { current = it }
    }

    /** 이전 세션의 늦은 정리가 현재 세션을 해제하지 않도록 객체 동일성을 확인한다. */
    @Synchronized fun release(lease: Lease): Boolean {
        if (current !== lease) return false
        current = null
        return true
    }
}
