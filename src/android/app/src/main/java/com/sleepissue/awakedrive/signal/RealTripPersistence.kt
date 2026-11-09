package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

data class RealTripOwnerState(val gear: Gear?, val power: PowerState?)

/** 실제 SDK 운행은 개입 모듈을 실행하거나 가짜 개입 평가를 만들어 저장하지 않는다. */
object RealTripCheckpointCodec {
    private const val PREFIX = "REAL_TRIP_V1:"

    fun encode(value: RealTripOwnerState): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(value.gear?.name ?: "")
            output.writeUTF(value.power?.name ?: "")
        }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(payload: String): RealTripOwnerState {
        require(payload.startsWith(PREFIX) && payload.length <= 256) { "SDK 운행 소유자 자료 형식이 다릅니다." }
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(payload.removePrefix(PREFIX)))).use { input ->
            val gear = input.readUTF().takeIf(String::isNotEmpty)?.let(Gear::valueOf)
            val power = input.readUTF().takeIf(String::isNotEmpty)?.let(PowerState::valueOf)
            require(input.available() == 0) { "SDK 운행 소유자 자료 뒤에 알 수 없는 값이 있습니다." }
            return RealTripOwnerState(gear, power)
        }
    }

    fun requireValid(snapshot: TripSnapshot, precise: TripRuleState) {
        require(snapshot.source == Source.REAL && snapshot.timeBasis == TripTimeBasis.WALL_EPOCH)
        val owner = decode(requireNotNull(snapshot.ownerState))
        require(owner.gear == precise.lastObservation?.gear && owner.power == precise.lastObservation?.power) {
            "SDK 운행 소유자의 현재 입력과 정밀 관측이 다릅니다."
        }
    }
}

class RealTripRestoreException(val reason: RepositoryFailure) : IllegalArgumentException(reason.name)

/** DB 읽기와 활성 상태 전환을 구분한다. 원본 snapshot은 변경하지 않는다. */
object RealTripPersistence {
    const val DATABASE_NAME = "awakedrive_sdk_trip.db"

    fun capture(precise: TripRuleState, policy: TripRulePolicy, clock: Clock): TripSnapshot = TripSnapshot(
        state = precise.trip,
        savedAtWallMs = clock.wallTimeMs(),
        driveSegments = precise.driveSegments,
        savedAtElapsedMs = clock.elapsedRealtimeMs(),
        preciseState = precise,
        savedAtScenarioMs = requireNotNull(precise.lastObservation).tMs,
        rulePolicy = policy,
        source = Source.REAL,
        timeBasis = TripTimeBasis.WALL_EPOCH,
        ownerState = RealTripCheckpointCodec.encode(RealTripOwnerState(
            precise.lastObservation?.gear, precise.lastObservation?.power)),
    )

    fun restore(snapshot: TripSnapshot, nowMs: Long, config: EngineConfig = EngineConfig()): TripRuleState {
        if (snapshot.source != Source.REAL || snapshot.timeBasis != TripTimeBasis.WALL_EPOCH) {
            throw RealTripRestoreException(RepositoryFailure.UNSUPPORTED_TIME_BASIS)
        }
        val precise = try {
            TripSnapshotValidator.requireValid(snapshot, config).also { RealTripCheckpointCodec.requireValid(snapshot, it) }
        } catch (_: Exception) { throw RealTripRestoreException(RepositoryFailure.INVALID_DATA) }
        if (nowMs < requireNotNull(snapshot.savedAtScenarioMs)) {
            throw RealTripRestoreException(RepositoryFailure.UNSUPPORTED_TIME_BASIS)
        }
        val rules = TripRules(config, requireNotNull(snapshot.rulePolicy)) {
            error("미관측 복원은 새 운행을 시작할 수 없습니다.")
        }
        return rules.resumeWithUnavailableInput(precise, nowMs)
    }
}
