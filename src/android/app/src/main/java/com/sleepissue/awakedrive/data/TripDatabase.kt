package com.sleepissue.awakedrive.data

import android.content.Context
import androidx.room.*

/** 공개 항목과 계산 재개 근거를 같은 행에 저장한다. 누적 합계는 구간으로 계산한다. */
@Entity(tableName = "trip_checkpoint")
data class TripCheckpointEntity(
    @PrimaryKey val id: Int = 1,
    val formatVersion: Int = 1,
    val tripId: String?,
    val continuousDriveSec: Long,
    val baselineRef: String?,
    val stage: String,
    val lastLat: Double?,
    val lastLon: Double?,
    val lastGear: String?,
    val runStartMs: Long?,
    val stopStartMs: Long?,
    val driveState: String,
    val continuousDriveMs: Long,
    val continuousBeforeStopMs: Long?,
    val provisionalStopSec: Long,
    val observationMs: Long?,
    val observationGear: String?,
    val observationPower: String?,
    val offStartedAtMs: Long?,
    val pendingNewTrip: Boolean,
    val restResetEmitted: Boolean,
    val segmentCount: Int,
    val savedAtScenarioMs: Long,
    val savedAtWallMs: Long,
    val savedAtElapsedMs: Long?,
    val stopTimePolicy: String,
    val shortStopPolicy: String,
    val source: String,
    val timeBasis: String,
    val ownerState: String,
)

@Entity(tableName = "drive_segment", primaryKeys = ["checkpointId", "ordinal"],
    foreignKeys = [ForeignKey(entity = TripCheckpointEntity::class,
        parentColumns = ["id"], childColumns = ["checkpointId"], onDelete = ForeignKey.CASCADE)])
data class DriveSegmentEntity(val checkpointId: Int = 1, val ordinal: Int, val startMs: Long, val endMs: Long)

data class TripCheckpointRows(val checkpoint: TripCheckpointEntity, val segments: List<DriveSegmentEntity>)

/** 트랜잭션은 상태 행과 구간 전체의 쓰기가 성공했을 때만 확정한다. */
@Dao
abstract class TripCheckpointDao {
    @Query("SELECT * FROM trip_checkpoint WHERE id = 1")
    abstract suspend fun checkpoint(): TripCheckpointEntity?

    @Query("SELECT * FROM drive_segment WHERE checkpointId = 1 ORDER BY ordinal")
    abstract suspend fun segments(): List<DriveSegmentEntity>

    @Insert
    abstract suspend fun insertCheckpoint(value: TripCheckpointEntity)

    @Insert
    abstract suspend fun insertSegments(value: List<DriveSegmentEntity>)

    @Query("DELETE FROM trip_checkpoint")
    abstract suspend fun deleteCheckpoint()

    @Transaction
    open suspend fun replace(checkpoint: TripCheckpointEntity, segments: List<DriveSegmentEntity>) {
        deleteCheckpoint()
        insertCheckpoint(checkpoint)
        insertSegments(segments)
    }

    @Transaction
    open suspend fun readConsistent(): TripCheckpointRows? {
        val value = checkpoint() ?: return null
        return TripCheckpointRows(value, segments())
    }

    @Transaction
    open suspend fun deleteAll() { deleteCheckpoint() }
}

@Database(entities = [TripCheckpointEntity::class, DriveSegmentEntity::class], version = 1, exportSchema = true)
abstract class TripDatabase : RoomDatabase() {
    abstract fun checkpointDao(): TripCheckpointDao

    companion object {
        fun open(context: Context, name: String): TripDatabase =
            Room.databaseBuilder(context.applicationContext, TripDatabase::class.java, name).build()
    }
}
