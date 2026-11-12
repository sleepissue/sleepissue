package com.sleepissue.awakedrive.cabin

/** 음성 원본 대신 확인 직전의 말소리 유무만 전달한다. 취소하면 늦은 결과를 사용하지 않는다. */
interface CabinSpeechSampler {
    fun sample(onResult: (Boolean) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
}
