package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.TripSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 실제 실행을 대체하는 시험 전용 기록기다. 기본 응답은 성공이 아닌 접수 상태다. */
class FakeActionRecorder {
    val requests = mutableListOf<ExecutionRequest>()
    val cancellations = mutableListOf<RequestId>()
    var startStatus = ExecutionStatus.REQUESTED
    var startFailure: ExecutionFailure? = null

    fun start(request: ExecutionRequest): ExecutionResult {
        requests += request
        return ExecutionResult(request.requestId, startStatus, startFailure)
    }

    fun cancel(requestId: RequestId): ExecutionResult {
        cancellations += requestId
        return ExecutionResult(requestId, ExecutionStatus.CANCELLED)
    }
}

class FakeCheckPort : CheckPort {
    val recorder = FakeActionRecorder()
    private val mutableExecutionResults = MutableSharedFlow<ExecutionResult>()
    override val executionResults = mutableExecutionResults.asSharedFlow()
    private val mutableResults = MutableSharedFlow<CheckResult>()
    override val results = mutableResults.asSharedFlow()
    override suspend fun start(request: CheckRequest) = recorder.start(request)
    override suspend fun cancel(requestId: RequestId) = recorder.cancel(requestId)
    suspend fun progress(result: ExecutionResult) = mutableExecutionResults.emit(result)
    suspend fun complete(result: CheckResult) = mutableResults.emit(result)
}

class FakeSpeechPort : SpeechPort {
    val recorder = FakeActionRecorder()
    private val mutableResults = MutableSharedFlow<ExecutionResult>()
    override val results = mutableResults.asSharedFlow()
    override suspend fun start(request: SpeechRequest) = recorder.start(request)
    override suspend fun cancel(requestId: RequestId) = recorder.cancel(requestId)
    suspend fun complete(result: ExecutionResult) = mutableResults.emit(result)
}

class FakeRestPort : RestPort {
    val recorder = FakeActionRecorder()
    private val mutableResults = MutableSharedFlow<RestResult>()
    override val results = mutableResults.asSharedFlow()
    override suspend fun start(request: RestRequest) = recorder.start(request)
    override suspend fun cancel(requestId: RequestId) = recorder.cancel(requestId)
    suspend fun complete(result: RestResult) = mutableResults.emit(result)
}

class FakeClimatePort : ClimatePort {
    val recorder = FakeActionRecorder()
    private val mutableResults = MutableSharedFlow<ExecutionResult>()
    override val results = mutableResults.asSharedFlow()
    override suspend fun start(request: ClimateRequest) = recorder.start(request)
    override suspend fun cancel(requestId: RequestId) = recorder.cancel(requestId)
    suspend fun complete(result: ExecutionResult) = mutableResults.emit(result)
}

/** DB가 아닌 메모리 Fake다. 실패를 주입하면 이전 스냅숏을 변경하지 않는다. */
class FakeTripRepository : TripRepository {
    var failure: RepositoryFailure? = null
    private var snapshot: TripSnapshot? = null

    override suspend fun load(): TripLoadResult {
        failure?.let { return TripLoadResult.Failed(it) }
        return snapshot?.let { TripLoadResult.Loaded(it) } ?: TripLoadResult.Empty
    }

    override suspend fun save(snapshot: TripSnapshot): RepositoryResult {
        failure?.let { return RepositoryResult.Failed(it) }
        this.snapshot = snapshot
        return RepositoryResult.Succeeded
    }

    override suspend fun delete(): RepositoryResult {
        failure?.let { return RepositoryResult.Failed(it) }
        snapshot = null
        return RepositoryResult.Succeeded
    }
}
