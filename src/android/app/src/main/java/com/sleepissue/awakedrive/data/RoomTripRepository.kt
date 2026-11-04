package com.sleepissue.awakedrive.data

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.CancellationException

/** Room의 suspend DAO가 I/O를 실행한다. 실패·손상 자료를 빈 DB나 성공으로 바꾸지 않는다. */
class RoomTripRepository(private val dao: TripCheckpointDao, private val config: EngineConfig = EngineConfig()) : TripRepository {
    override suspend fun save(snapshot: TripSnapshot): RepositoryResult {
        val rows = try {
            val precise = TripSnapshotValidator.requireValid(snapshot, config)
            val rulePolicy = requireNotNull(snapshot.rulePolicy)
            val owner = requireNotNull(snapshot.ownerState)
            val decodedOwner = TripSessionCodec.decode(owner)
            decodedOwner.intervention.requireValid(requireNotNull(snapshot.savedAtScenarioMs), snapshot.state)
            require(decodedOwner.gear == precise.lastObservation?.gear && decodedOwner.power == precise.lastObservation?.power)
            val trip = snapshot.state
            TripCheckpointRows(TripCheckpointEntity(tripId = trip.tripId,
                continuousDriveSec = trip.continuousDriveSec, baselineRef = trip.baselineRef,
                stage = trip.stage.name, lastLat = trip.lastLocation?.lat, lastLon = trip.lastLocation?.lon,
                lastGear = trip.lastGear?.name, runStartMs = trip.runStartMs, stopStartMs = trip.stopStartMs,
                driveState = trip.driveState.name, continuousDriveMs = precise.continuousDriveMs,
                continuousBeforeStopMs = precise.continuousBeforeStopMs, provisionalStopSec = trip.provisionalStopSec,
                observationMs = precise.lastObservation?.tMs, observationGear = precise.lastObservation?.gear?.name,
                observationPower = precise.lastObservation?.power?.name, offStartedAtMs = precise.offStartedAtMs,
                pendingNewTrip = precise.pendingNewTrip, restResetEmitted = precise.restResetEmitted,
                segmentCount = snapshot.driveSegments.size,
                savedAtScenarioMs = requireNotNull(snapshot.savedAtScenarioMs), savedAtWallMs = snapshot.savedAtWallMs,
                savedAtElapsedMs = snapshot.savedAtElapsedMs, stopTimePolicy = rulePolicy.stopTime.name,
                shortStopPolicy = rulePolicy.shortStopAccumulation.name, source = snapshot.source!!.name,
                timeBasis = snapshot.timeBasis!!.name, ownerState = owner),
                snapshot.driveSegments.mapIndexed { index, segment ->
                    DriveSegmentEntity(ordinal = index, startMs = segment.startMs, endMs = segment.endMs)
                })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return RepositoryResult.Failed(RepositoryFailure.INVALID_DATA) }
        return try {
            dao.replace(rows.checkpoint, rows.segments)
            RepositoryResult.Succeeded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE) }
    }

    override suspend fun load(): TripLoadResult {
        val rows = try { dao.readConsistent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return TripLoadResult.Failed(RepositoryFailure.UNAVAILABLE) }
        if (rows == null) return TripLoadResult.Empty
        if (rows.checkpoint.formatVersion != 1) return TripLoadResult.Failed(RepositoryFailure.UNSUPPORTED_VERSION)
        if (rows.checkpoint.observationMs?.let { it < rows.checkpoint.savedAtScenarioMs } == true)
            return TripLoadResult.Failed(RepositoryFailure.STALE_INPUT)
        return try {
            val row = rows.checkpoint
            require(row.id == 1 && row.segmentCount == rows.segments.size && rows.segments.map { it.ordinal } == rows.segments.indices.toList())
            val segments = rows.segments.map { require(it.checkpointId == row.id); DriveSegment(it.startMs, it.endMs) }
            val policy = TripRulePolicy(StopTimePolicy.valueOf(row.stopTimePolicy), ShortStopAccumulationPolicy.valueOf(row.shortStopPolicy))
            val observation = row.observationMs?.let {
                TripObservation(it, row.observationGear?.let(Gear::valueOf), row.observationPower?.let(PowerState::valueOf))
            }
            require(observation != null || row.observationGear == null && row.observationPower == null)
            val duration = row.stopStartMs?.let { row.savedAtScenarioMs - it } ?: 0
            val includedStop = if (row.runStartMs != null && row.stopStartMs != null && duration in 0 until config.tripStopPauseMs &&
                policy.shortStopAccumulation == ShortStopAccumulationPolicy.INCLUDE) duration else 0
            val total = RollingDriveWindow(config.rollingDriveWindowMs).summarize(segments, row.savedAtScenarioMs).durationMs
            require((row.lastLat == null) == (row.lastLon == null))
            val trip = TripState(row.tripId, row.continuousDriveSec, Math.addExact(total, includedStop) / 1_000,
                row.baselineRef, InterventionStage.valueOf(row.stage),
                row.lastLat?.let { GeoPoint(it, requireNotNull(row.lastLon)) }, row.lastGear?.let(Gear::valueOf),
                row.runStartMs, row.stopStartMs, DriveState.valueOf(row.driveState),
                row.continuousBeforeStopMs?.div(1_000), row.provisionalStopSec)
            val precise = TripRuleState(trip, row.continuousDriveMs, segments, observation, row.stopStartMs,
                row.continuousBeforeStopMs, row.offStartedAtMs, row.pendingNewTrip, row.restResetEmitted)
            val snapshot = TripSnapshot(trip, row.savedAtWallMs, segments, row.savedAtElapsedMs,
                precise, row.savedAtScenarioMs, policy, Source.valueOf(row.source), TripTimeBasis.valueOf(row.timeBasis), row.ownerState)
            TripSnapshotValidator.requireValid(snapshot, config)
            val owner = TripSessionCodec.decode(row.ownerState)
            owner.intervention.requireValid(row.savedAtScenarioMs, trip)
            require(owner.gear == observation?.gear && owner.power == observation?.power)
            TripLoadResult.Loaded(snapshot)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { TripLoadResult.Failed(RepositoryFailure.INVALID_DATA) }
    }

    override suspend fun delete(): RepositoryResult = try {
        dao.deleteAll()
        RepositoryResult.Succeeded
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE) }
}
