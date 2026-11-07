package com.sleepissue.awakedrive.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.rest.RewardOutcome
import com.sleepissue.awakedrive.session.RestRecord
import com.sleepissue.awakedrive.session.RestRecordStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// [4번] 휴식 인증·보상 기록. 1번 TripDatabase와 파일을 나눠 스키마 변경이 서로 엮이지 않게 했다.

@Entity(tableName = "rest_record")
data class RestRecordEntity(
    @PrimaryKey val sessionId: String,
    val areaId: String,
    val areaName: String,
    val lat: Double,
    val lon: Double,
    val certifiedAtWallMs: Long,
    val driveSecBeforeRest: Long?,
    val maxStageBeforeRest: String,
    val outcome: String,
)

@Dao
interface RestRecordDao {
    @Query("SELECT * FROM rest_record ORDER BY certifiedAtWallMs")
    suspend fun all(): List<RestRecordEntity>

    @Query("SELECT * FROM rest_record ORDER BY certifiedAtWallMs")
    fun observe(): Flow<List<RestRecordEntity>>

    /** @return 새로 넣은 행 번호, 이미 있으면 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: RestRecordEntity): Long

    @Query("DELETE FROM rest_record")
    suspend fun deleteAll()
}

@Database(entities = [RestRecordEntity::class], version = 1, exportSchema = true)
abstract class RestRecordDatabase : RoomDatabase() {
    abstract fun dao(): RestRecordDao

    companion object {
        const val NAME = "rest_records.db"
        fun open(context: Context, name: String = NAME): RestRecordDatabase =
            Room.databaseBuilder(context.applicationContext, RestRecordDatabase::class.java, name).build()
    }
}

class RoomRestRecordStore(private val dao: RestRecordDao) : RestRecordStore {
    override suspend fun all(): List<RestRecord> = dao.all().map { it.toRecord() }
    override suspend fun insert(record: RestRecord): Boolean = dao.insert(record.toEntity()) != -1L
    override suspend fun deleteAll() = dao.deleteAll()
    override fun observe(): Flow<List<RestRecord>> = dao.observe().map { rows -> rows.map { it.toRecord() } }
}

private fun RestRecord.toEntity() = RestRecordEntity(
    sessionId = sessionId,
    areaId = areaId,
    areaName = areaName,
    lat = location.lat,
    lon = location.lon,
    certifiedAtWallMs = certifiedAtWallMs,
    driveSecBeforeRest = driveSecBeforeRest,
    maxStageBeforeRest = maxStageBeforeRest.name,
    outcome = outcome.name,
)

private fun RestRecordEntity.toRecord() = RestRecord(
    sessionId = sessionId,
    areaId = areaId,
    areaName = areaName,
    location = GeoPoint(lat, lon),
    certifiedAtWallMs = certifiedAtWallMs,
    driveSecBeforeRest = driveSecBeforeRest,
    maxStageBeforeRest = InterventionStage.valueOf(maxStageBeforeRest),
    outcome = RewardOutcome.valueOf(outcome),
)
