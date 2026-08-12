package com.raydon.moji.android.data

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "moji_state")
data class SnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val json: String,
    val updatedAtMillis: Long,
)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM moji_state WHERE id = 1")
    fun observe(): Flow<SnapshotEntity?>

    @Query("SELECT * FROM moji_state WHERE id = 1")
    suspend fun get(): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SnapshotEntity)
}

@Database(entities = [SnapshotEntity::class], version = 1, exportSchema = true)
abstract class MojiDatabase : RoomDatabase() {
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        @Volatile private var instance: MojiDatabase? = null

        fun get(context: Context): MojiDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MojiDatabase::class.java,
                "moji.db",
            ).build().also { instance = it }
        }
    }
}
